package com.agentgate.user.service;

import com.agentgate.user.domain.Role;
import com.agentgate.user.domain.User;
import com.agentgate.user.repository.UserRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * With no users yet (a new installation, or one from before users existed), creates the
 * configured administrator (agentgate.admin.*) so existing deployments keep signing in.
 * Afterwards users are managed in the console; the configured password is not used again.
 */
@Component
@Order(10)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          @Value("${agentgate.admin.username}") String username,
                          @Value("${agentgate.admin.password}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        userRepository.save(new User(username, "Administrator", passwordEncoder.encode(password), Set.of(Role.ADMIN)));
        log.info("Created the initial administrator '{}' from agentgate.admin settings", username);
    }
}
