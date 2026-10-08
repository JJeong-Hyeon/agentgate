package com.agentgate.user.service;

import com.agentgate.common.exception.DuplicateUserException;
import com.agentgate.common.exception.InvalidUserChangeException;
import com.agentgate.common.exception.UserNotFoundException;
import com.agentgate.user.domain.Role;
import com.agentgate.user.domain.User;
import com.agentgate.user.dto.PasswordChangeRequest;
import com.agentgate.user.dto.UserRequest;
import com.agentgate.user.dto.UserResponse;
import com.agentgate.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse create(UserRequest request) {
        if (request.password() == null) {
            throw new InvalidUserChangeException("password: required for a new user");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateUserException(request.username());
        }
        User user = new User(request.username(), request.displayName(), passwordEncoder.encode(request.password()),
                request.roles());
        if (!request.enabledOrDefault()) {
            user.update(request.displayName(), request.roles(), false);
        }
        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateUserException(request.username());
        }
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return userRepository.findAllByOrderByUsernameAsc().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return UserResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public UserResponse me(String username) {
        return userRepository.findByUsername(username).map(UserResponse::from)
                .orElseThrow(() -> new InvalidUserChangeException("unknown user"));
    }

    /** Updates name, roles, enabled and (when given) the password; never the username. */
    @Transactional
    public UserResponse update(Long id, UserRequest request, String actingUser) {
        User user = findOrThrow(id);
        boolean staysAdmin = request.enabledOrDefault() && request.roles().contains(Role.ADMIN);
        if (user.isAdmin() && !staysAdmin) {
            requireAnotherAdmin(user);
            if (user.getUsername().equals(actingUser)) {
                throw new InvalidUserChangeException("you cannot remove your own administrator access");
            }
        }
        user.update(request.displayName(), request.roles(), request.enabledOrDefault());
        if (request.password() != null) {
            user.changePassword(passwordEncoder.encode(request.password()));
        }
        return UserResponse.from(user);
    }

    @Transactional
    public void delete(Long id, String actingUser) {
        User user = findOrThrow(id);
        if (user.getUsername().equals(actingUser)) {
            throw new InvalidUserChangeException("you cannot delete yourself");
        }
        if (user.isAdmin()) {
            requireAnotherAdmin(user);
        }
        userRepository.delete(user);
    }

    @Transactional
    public void changeOwnPassword(String username, PasswordChangeRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new InvalidUserChangeException("unknown user"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new InvalidUserChangeException("currentPassword: does not match");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    // The console must always keep someone who can manage users.
    private void requireAnotherAdmin(User leaving) {
        long others = userRepository.findAll().stream()
                .filter(User::isAdmin)
                .filter(u -> !u.getId().equals(leaving.getId()))
                .count();
        if (others == 0) {
            throw new InvalidUserChangeException("at least one enabled administrator must remain");
        }
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
