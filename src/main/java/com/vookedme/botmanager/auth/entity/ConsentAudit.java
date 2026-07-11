package com.vookedme.botmanager.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Append-only GDPR consent acceptance record.
 *
 * <p>One row per acceptance event. Never updated or deleted from service code.
 * The cascade from {@code users} only applies if a user is hard-deleted, which
 * is prohibited at the application layer — in practice these rows persist
 * indefinitely, making the consent audit durable.
 *
 * <p>{@code acceptedAt} uses {@link OffsetDateTime} to map to PostgreSQL
 * {@code TIMESTAMPTZ} and forces storage in UTC with an explicit offset —
 * a requirement for GDPR regulatory audit evidence. Ambiguous wall-clock
 * timestamps are not acceptable for consent records.
 *
 * <p>No {@code @Version} — there is no concurrent-update scenario on
 * append-only rows.
 */
@Entity
@Table(name = "consent_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsentAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 50)
    private ConsentType consentType;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "accepted_at", nullable = false)
    private OffsetDateTime acceptedAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Builder.Default
    @Column(nullable = false, length = 10)
    private String language = "es";

    /**
     * SHA-256 checksum of the legal text shown to the user at the time of
     * acceptance. Null if not configured. Used to prove, in a regulatory audit,
     * that the user accepted a specific version of the text.
     */
    @Column(name = "checksum_legal", length = 64)
    private String checksumLegal;
}
