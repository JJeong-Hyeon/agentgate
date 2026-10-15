package com.agentgate.user.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.user.domain.Role;
import com.agentgate.user.repository.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** The role change from the IdP has to reach the database, not just the object in memory. */
@SpringBootTest
@ActiveProfiles("test")
class OidcUserProvisionerPersistenceTest {

    @Autowired private OidcUserProvisioner provisioner;
    @Autowired private UserRepository users;

    @Test
    void aLaterSignInSavesTheRolesTheIdpNowGives() {
        provisioner.signIn("sub-persist", "persist-hana", "Hana", Set.of(Role.ADMIN, Role.EDITOR));
        provisioner.signIn("sub-persist", "persist-hana", "Hana Kim", Set.of(Role.VIEWER));

        var stored = users.findByUsername("persist-hana").orElseThrow();
        assertThat(stored.getRoles()).containsExactly(Role.VIEWER);
        assertThat(stored.getDisplayName()).isEqualTo("Hana Kim");
        users.delete(stored);
    }

    @Test
    void oneIdpIdentityIsOneUserEvenAfterTheIdpRenamesItOrHandsItsNameToSomeoneElse() {
        provisioner.signIn("sub-ren", "rena", "Rena", Set.of(Role.EDITOR));

        var renamed = provisioner.signIn("sub-ren", "rena.new", "Rena", Set.of(Role.EDITOR));
        assertThat(renamed.getUsername()).isEqualTo("rena");
        assertThat(users.findByUsername("rena.new")).isEmpty();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> provisioner.signIn("sub-someone-else", "rena", "Newcomer", Set.of(Role.ADMIN)))
                .isInstanceOf(OidcUserProvisioner.Refused.class);
        assertThat(users.findByOidcSubject("sub-ren").orElseThrow().getRoles()).containsExactly(Role.EDITOR);
        users.delete(users.findByUsername("rena").orElseThrow());
    }
}
