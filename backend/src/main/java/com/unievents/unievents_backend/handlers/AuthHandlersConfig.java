package com.unievents.unievents_backend.handlers;

import com.unievents.unievents_backend.network.server.RequestDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing a pre-configured {@link RequestDispatcher}
 * with all Member 01 authentication handlers registered.
 */
@Configuration
public class AuthHandlersConfig {

    @Bean
    public RequestDispatcher requestDispatcher(
            RegisterHandler registerHandler,
            LoginHandler loginHandler,
            LogoutHandler logoutHandler,
            SessionCheckHandler sessionCheckHandler
    ) {
        RequestDispatcher dispatcher = new RequestDispatcher();
        registerHandlers(dispatcher, registerHandler, loginHandler, logoutHandler, sessionCheckHandler);
        return dispatcher;
    }

    public static void registerHandlers(
            RequestDispatcher dispatcher,
            RegisterHandler registerHandler,
            LoginHandler loginHandler,
            LogoutHandler logoutHandler,
            SessionCheckHandler sessionCheckHandler
    ) {
        dispatcher.registerHandler(RegisterHandler.VERB, registerHandler);
        dispatcher.registerHandler(LoginHandler.VERB, loginHandler);
        dispatcher.registerHandler(LogoutHandler.VERB, logoutHandler);
        dispatcher.registerHandler(SessionCheckHandler.VERB, sessionCheckHandler);
    }
}
