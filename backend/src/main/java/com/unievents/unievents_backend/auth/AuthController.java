package com.unievents.unievents_backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();

    public AuthController(JdbcTemplate db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
        this.dummyHash = passwords.encode("unused-account-password");
    }

    public record Registration(@NotBlank @Size(max=100) String name,
        @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(min=8, max=72) String password) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=72) String password) {}
    public record User(long id, String name, String email) {}
    private record Account(User user, String hash, String role) {}

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public User register(@Valid @RequestBody Registration input,
                         HttpServletRequest request, HttpServletResponse response) {
        checkPasswordLength(input.password());
        String email = normalize(input.email());
        User user;
        try {
            db.update("INSERT INTO app_users(name,email,password_hash) VALUES (?,?,?)",
                input.name().strip(), email, passwords.encode(input.password()));
            user = db.queryForObject("SELECT id,name,email FROM app_users WHERE email=?",
                (rs, row) -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")), email);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        signIn(user, "STUDENT", request, response);
        return user;
    }

    @PostMapping("/login")
    public User login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        checkPasswordLength(input.password());
        List<Account> accounts = db.query("SELECT id,name,email,password_hash,role FROM app_users WHERE email=?",
            (rs, row) -> new Account(new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")), rs.getString("password_hash"), rs.getString("role")),
            normalize(input.email()));
        boolean matches = passwords.matches(input.password(), accounts.isEmpty() ? dummyHash : accounts.getFirst().hash());
        if (accounts.isEmpty() || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        User user = accounts.getFirst().user();
        signIn(user, accounts.getFirst().role(), request, response);
        return user;
    }

    @GetMapping("/me")
    public User me(Principal principal) {
        return db.queryForObject("SELECT id,name,email FROM app_users WHERE email=?",
            (rs, row) -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")), principal.getName());
    }

    private void signIn(User user, String role, HttpServletRequest request, HttpServletResponse response) {
        request.getSession();
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.email(), null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
            "ADMIN".equals(role) ? "ROLE_ADMIN" : "ROLE_STUDENT"))));
        SecurityContextHolder.setContext(context);
        sessions.saveContext(context, request, response);
    }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static void checkPasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes.");
        }
    }
}
