package com.xammer.cloud.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.Collection;

public class ClientUserDetails extends User {
    private final Long clientId;

    public ClientUserDetails(String username, String password, Collection<? extends GrantedAuthority> authorities, Long clientId) {
        super(username, password, authorities);
        this.clientId = clientId;
    }

    public Long getClientId() {
        return clientId;
    }
}
