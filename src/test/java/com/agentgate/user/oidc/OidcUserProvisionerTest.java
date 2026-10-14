package com.agentgate.user.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentgate.user.domain.AuthSource;
import com.agentgate.user.domain.Role;
import com.agentgate.user.domain.User;
import com.agentgate.user.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class OidcUserProvisionerTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final OidcUserProvisioner provisioner = new OidcUserProvisioner(users, encoder);

    OidcUserProvisionerTest() {
        when(encoder.encode(any())).thenReturn("hash-of-a-random-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(users.findByOidcSubject(any())).thenReturn(Optional.empty());
        when(users.findByUsername(any())).thenReturn(Optional.empty());
    }

    @Test
    void aFirstSignInCreatesAnSsoUserTiedToTheIdpSubject() {
        User created = provisioner.signIn("sub-1", "hana", "Hana Kim", Set.of(Role.EDITOR));

        assertThat(created.getAuthSource()).isEqualTo(AuthSource.OIDC);
        assertThat(created.getOidcSubject()).isEqualTo("sub-1");
        assertThat(created.getRoles()).containsExactly(Role.EDITOR);
        assertThat(created.isEnabled()).isTrue();
    }

    @Test
    void laterSignInsFollowTheIdpWhenGroupsChange() {
        User existing = User.fromIdp("sub-1", "hana", "Hana", "hash", Set.of(Role.ADMIN, Role.EDITOR));
        when(users.findByOidcSubject("sub-1")).thenReturn(Optional.of(existing));

        provisioner.signIn("sub-1", "hana", "Hana Kim", Set.of(Role.VIEWER));

        assertThat(existing.getRoles()).containsExactly(Role.VIEWER);  // the administrator role is gone
        assertThat(existing.getDisplayName()).isEqualTo("Hana Kim");
    }

    @Test
    void theSameIdentityKeepsItsConsoleUserWhenTheIdpRenamesIt() {
        User existing = User.fromIdp("sub-1", "hana", "Hana", "hash", Set.of(Role.EDITOR));
        when(users.findByOidcSubject("sub-1")).thenReturn(Optional.of(existing));

        User signedIn = provisioner.signIn("sub-1", "hana.kim", "Hana Kim", Set.of(Role.EDITOR));

        assertThat(signedIn).isSameAs(existing);
        assertThat(signedIn.getUsername()).isEqualTo("hana");
        verify(users, never()).save(any());
    }

    @Test
    void anotherIdentityCannotTakeOverANameThatBelongsToSomeoneElse() {
        // "hana" left the company and the IdP handed her name to a newcomer, who has a different subject.
        User hana = User.fromIdp("sub-1", "hana", "Hana", "hash", Set.of(Role.ADMIN));
        when(users.findByUsername("hana")).thenReturn(Optional.of(hana));

        assertThatThrownBy(() -> provisioner.signIn("sub-2", "hana", "Newcomer", Set.of(Role.ADMIN)))
                .isInstanceOf(OidcUserProvisioner.Refused.class)
                .hasMessageContaining("different identity");
        assertThat(hana.getOidcSubject()).isEqualTo("sub-1");
        assertThat(hana.getDisplayName()).isEqualTo("Hana");
        verify(users, never()).save(any());
    }

    @Test
    void anSsoUserMadeBeforeSubjectsWerekeptIsTiedToTheNextIdentityThatSignsInAsThem() {
        User legacy = User.fromIdp(null, "hana", "Hana", "hash", Set.of(Role.EDITOR));
        when(users.findByUsername("hana")).thenReturn(Optional.of(legacy));

        provisioner.signIn("sub-1", "hana", "Hana", Set.of(Role.EDITOR));

        assertThat(legacy.getOidcSubject()).isEqualTo("sub-1");
    }

    @Test
    void aLocalAccountCannotBeTakenOverThroughSso() {
        User local = new User("admin", "Administrator", "hash", Set.of(Role.ADMIN));
        when(users.findByUsername("admin")).thenReturn(Optional.of(local));

        assertThatThrownBy(() -> provisioner.signIn("sub-9", "admin", "Someone", Set.of(Role.ADMIN)))
                .isInstanceOf(OidcUserProvisioner.Refused.class)
                .hasMessageContaining("local account");
        assertThat(local.getRoles()).containsExactly(Role.ADMIN);
        assertThat(local.getOidcSubject()).isNull();
        verify(users, never()).save(any());
    }

    @Test
    void aDisabledSsoUserStaysOut() {
        User disabled = User.fromIdp("sub-1", "hana", "Hana", "hash", Set.of(Role.VIEWER));
        disabled.update("Hana", Set.of(Role.VIEWER), false);
        when(users.findByOidcSubject("sub-1")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> provisioner.signIn("sub-1", "hana", "Hana", Set.of(Role.ADMIN)))
                .isInstanceOf(OidcUserProvisioner.Refused.class)
                .hasMessageContaining("disabled");
        assertThat(disabled.getRoles()).containsExactly(Role.VIEWER);
    }

    @Test
    void noRolesNoSubjectOrNoUsableNameRefusesTheSignIn() {
        assertThatThrownBy(() -> provisioner.signIn("sub-1", "hana", "Hana", Set.of()))
                .isInstanceOf(OidcUserProvisioner.Refused.class);
        assertThatThrownBy(() -> provisioner.signIn(null, "hana", "Hana", Set.of(Role.VIEWER)))
                .isInstanceOf(OidcUserProvisioner.Refused.class);
        assertThatThrownBy(() -> provisioner.signIn("sub-1", null, "Hana", Set.of(Role.VIEWER)))
                .isInstanceOf(OidcUserProvisioner.Refused.class);
        assertThatThrownBy(() -> provisioner.signIn("sub-1", "x".repeat(65), "Hana", Set.of(Role.VIEWER)))
                .isInstanceOf(OidcUserProvisioner.Refused.class);
        assertThatThrownBy(() -> provisioner.signIn("sub-1", "홍길동", "Hong", Set.of(Role.VIEWER)))
                .isInstanceOf(OidcUserProvisioner.Refused.class);
        verify(users, never()).save(any());
    }
}
