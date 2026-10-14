package com.agentgate.user.oidc;

import com.agentgate.user.domain.AuthSource;
import com.agentgate.user.domain.Role;
import com.agentgate.user.domain.User;
import com.agentgate.user.repository.UserRepository;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the console user for a person the IdP vouched for, and keeps their roles in step with it. */
@Service
@RequiredArgsConstructor
public class OidcUserProvisioner {

    private static final int MAX_SUBJECT = 255;
    // The same shape the console accepts for a user name, so an administrator can still edit this user.
    private static final java.util.regex.Pattern USERNAME = java.util.regex.Pattern.compile("^[A-Za-z0-9._@-]{3,64}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** Thrown to refuse the sign-in; the message is for the log, never shown to the person. */
    public static class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    @Transactional
    public User signIn(String subject, String username, String displayName, Set<Role> roles) {
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT) {
            throw new Refused("the identity provider gave no usable subject");
        }
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new Refused("the identity provider gave no usable user name (letters, digits and . _ @ - only, 3 to 64 characters)");
        }
        if (roles.isEmpty()) {
            throw new Refused("'%s' is in no group that maps to a role".formatted(username));
        }
        // The IdP's own id is who this is; the name it shows can change or be handed to someone else.
        User user = userRepository.findByOidcSubject(subject).orElse(null);
        if (user == null) {
            user = userRepository.findByUsername(username).orElse(null);
            if (user == null) {
                // The password is random and thrown away: this person signs in only through the IdP.
                String unusable = passwordEncoder.encode(UUID.randomUUID().toString());
                return userRepository.save(User.fromIdp(subject, username, displayName, unusable, roles));
            }
            if (user.getAuthSource() != AuthSource.OIDC) {
                // Otherwise anyone the IdP lets pick the name "admin" would take over the local administrator.
                throw new Refused("'%s' is a local account and cannot be signed in to through SSO".formatted(username));
            }
            if (user.getOidcSubject() != null) {
                throw new Refused("'%s' belongs to a different identity at the identity provider".formatted(username));
            }
            user.bindSubject(subject);  // made before subjects were kept: the next sign-in claims it
        }
        if (!user.isEnabled()) {
            throw new Refused("'%s' is disabled".formatted(user.getUsername()));
        }
        user.syncFromIdp(displayName, roles);
        return user;
    }
}
