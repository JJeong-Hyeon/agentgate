package com.agentgate.user.oidc;

import com.agentgate.user.domain.Role;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Turns the IdP's group names into console roles. Group names that map to nothing are ignored. */
public class OidcRoleMapper {

    private final OidcProperties properties;

    public OidcRoleMapper(OidcProperties properties) {
        this.properties = properties;
    }

    /** Empty when the person is in no mapped group and no default role is configured. */
    public Set<Role> rolesFor(Collection<String> groups) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        addIfMember(roles, Role.ADMIN, properties.adminGroups(), groups);
        addIfMember(roles, Role.EDITOR, properties.editorGroups(), groups);
        addIfMember(roles, Role.APPROVER, properties.approverGroups(), groups);
        addIfMember(roles, Role.VIEWER, properties.viewerGroups(), groups);
        if (roles.isEmpty() && properties.defaultRole() != null) {
            roles.add(properties.defaultRole());
        }
        return roles;
    }

    private static void addIfMember(Set<Role> roles, Role role, List<String> granting, Collection<String> groups) {
        if (groups.stream().anyMatch(granting::contains)) {
            roles.add(role);
        }
    }
}
