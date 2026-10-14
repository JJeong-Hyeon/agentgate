package com.agentgate.user.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.user.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

class OidcRoleMapperTest {

    private static OidcProperties properties(Role defaultRole) {
        return new OidcProperties(true, "http://idp/realms/x", "agentgate", "secret", null, null, null,
                List.of("agentgate-admins"), List.of("agentgate-editors"), List.of("agentgate-approvers"),
                List.of("agentgate-viewers", "everyone-in-research"), defaultRole, null);
    }

    @Test
    void groupsGrantTheRolesTheyAreListedUnder() {
        OidcRoleMapper mapper = new OidcRoleMapper(properties(null));

        assertThat(mapper.rolesFor(List.of("agentgate-editors", "agentgate-approvers")))
                .containsExactlyInAnyOrder(Role.EDITOR, Role.APPROVER);
        assertThat(mapper.rolesFor(List.of("everyone-in-research"))).containsExactly(Role.VIEWER);
    }

    @Test
    void groupsThatMapToNothingAreIgnoredAndLeaveNoRoleByDefault() {
        OidcRoleMapper mapper = new OidcRoleMapper(properties(null));

        assertThat(mapper.rolesFor(List.of("sales", "AGENTGATE-ADMINS"))).isEmpty();  // names match exactly
        assertThat(mapper.rolesFor(List.of())).isEmpty();
    }

    @Test
    void aDefaultRoleOnlyAppliesWhenNothingElseMatched() {
        OidcRoleMapper mapper = new OidcRoleMapper(properties(Role.VIEWER));

        assertThat(mapper.rolesFor(List.of("sales"))).containsExactly(Role.VIEWER);
        assertThat(mapper.rolesFor(List.of("agentgate-admins"))).containsExactly(Role.ADMIN);
    }
}
