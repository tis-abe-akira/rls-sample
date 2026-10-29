package com.example.rlssample.user;

import com.example.rlssample.tenant.Role;

public record User(Long id, Long tenantId, Long orgId, String subject, String name, Role role) {
}
