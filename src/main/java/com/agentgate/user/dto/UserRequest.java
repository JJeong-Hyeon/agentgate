package com.agentgate.user.dto;

import com.agentgate.user.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Creating a user (password required) or updating one (username ignored; password optional). */
public record UserRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._@-]{3,64}$") String username,
        @Size(max = 255) String displayName,
        @Size(min = 8, max = 200) String password,
        @NotEmpty Set<Role> roles,
        Boolean enabled
) {
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
