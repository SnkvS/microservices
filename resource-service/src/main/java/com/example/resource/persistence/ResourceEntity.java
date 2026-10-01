package com.example.resource.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "resources")
public class ResourceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, columnDefinition = "bytea") public byte[] data;
    protected ResourceEntity() {}
    public ResourceEntity(byte[] data) { this.data = data; }
}
