package com.agentgate.user.dto;

import com.agentgate.user.domain.Role;
import com.agentgate.user.domain.User;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        Set<Role> roles,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), new TreeSet<>(user.getRoles()),
                user.isEnabled(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
