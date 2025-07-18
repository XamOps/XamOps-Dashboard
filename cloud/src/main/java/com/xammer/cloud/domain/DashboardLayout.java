package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Column;
import javax.persistence.Lob;

@Entity
@Data
@NoArgsConstructor
public class DashboardLayout {

    @Id
    private String username;

    @Lob
    @Column(nullable = false)
    private String layoutConfig; // Store layout as a JSON string

    public DashboardLayout(String username, String layoutConfig) {
        this.username = username;
        this.layoutConfig = layoutConfig;
    }
}
