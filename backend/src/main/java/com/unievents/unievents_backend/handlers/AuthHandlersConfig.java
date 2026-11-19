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
            SessionCheckHandler sessionCheckHandler,
            com.unievents.unievents_backend.domain.service.SessionManager sessionManager,
            com.unievents.unievents_backend.network.executor.MetricsProtocolHandler metricsHandler
    ) {
        RequestDispatcher dispatcher = new RequestDispatcher(sessionManager);
        registerHandlers(dispatcher, registerHandler, loginHandler, logoutHandler, sessionCheckHandler);
        dispatcher.registerHandler("GET_METRICS", metricsHandler);
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
