package com.vookedme.botmanager.customer;

import com.vookedme.botmanager.common.event.SourceActor;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.customer.entity.CustomerLegitimationAuditLog;
import com.vookedme.botmanager.customer.entity.CustomerLegitimationEventType;
import com.vookedme.botmanager.customer.entity.OriginOfLegitimation;
import com.vookedme.botmanager.customer.entity.ReasonOfDeny;
import com.vookedme.botmanager.customer.legitimation.CustomerLegitimacyService;
import com.vookedme.botmanager.customer.repository.CustomerLegitimationAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link CustomerLegitimacyService} against a real PostgreSQL instance
 * (Testcontainers).
 *
 * <p>Verifies on a real database: state transitions persist the customer state change and the
 * audit row in the same transaction (committed ⟺ audited, enforced by the append-only database
 * trigger); the silent no-op behaviour when {@code legitimateFromBot} is called over an explicit
 * opt-out; idempotency of each operation; and the {@code DENIED + NEVER_LEGITIMATED + ATTESTATION
 * → REACTIVATED/ATTESTATION/OWNER} transition path.
 *
 * <p>The {@code @Version} optimistic lock retry is exercised deterministically in the
 * {@code CustomerLegitimacyServiceTest} unit test (forcing a real race under a single transaction
 * is not reliably achievable in an integration test).
 *
 * <p>Depends on {@code BaseIntegrationTest} (Testcontainers PostgreSQL, fixture repositories,
 * and {@code testBusiness} seed) — published in a subsequent source batch.
 */
class CustomerLegitimacyServiceIT extends BaseIntegrationTest {

    @Autowired
    private CustomerLegitimacyService legitimacyService;

    @Autowired
    private CustomerLegitimationAuditLogRepository auditRepo;

    private Customer seed(String phone, Boolean channelLegitimacyStatus, ReasonOfDeny reason) {
        return customerRepository.save(Customer.builder()
                .business(testBusiness)
                .phone(phone)
                .channelLegitimacyStatus(channelLegitimacyStatus)
                .reasonOfDeny(reason)
                .build());
    }

    @Test
    void legitimateFromBot_fromUnevaluated_persistsStateAndAudit_sameTransaction() {
        Customer c = seed("+34600000011", null, null);
        assertThat(auditRepo.findByCustomerIdOrderByOccurredAtAsc(c.getId())).isEmpty();

        legitimacyService.legitimateFromBot(c.getId());

        // A single call produces both: the state change and exactly one audit row (atomic).
        Customer reloaded = customerRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getChannelLegitimacyStatus()).isTrue();
        assertThat(reloaded.getOriginOfLegitimation()).isEqualTo(OriginOfLegitimation.BOT_ORIGIN);
        assertThat(reloaded.getReasonOfDeny()).isNull();
        assertThat(reloaded.getLegitimatedAt()).isNotNull();

        List<CustomerLegitimationAuditLog> rows = auditRepo.findByCustomerIdOrderByOccurredAtAsc(c.getId());
        assertThat(rows).hasSize(1);
        CustomerLegitimationAuditLog row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(CustomerLegitimationEventType.LEGITIMATED);
        assertThat(row.getFromState()).isEqualTo("UNEVALUATED");
        assertThat(row.getToState()).isEqualTo("LEGITIMATE");
        assertThat(row.getOrigin()).isEqualTo("BOT_ORIGIN");
        assertThat(row.getTriggeredBy()).isEqualTo(SourceActor.CUSTOMER);
        assertThat(row.getActorUserId()).isNull();
        assertThat(row.getBusinessId()).isEqualTo(testBusiness.getId());
    }

    @Test
    void optOut_persistsDeniedAndAudit() {
        Customer c = seed("+34600000012", true, null);

        legitimacyService.optOut(c.getId());

        Customer reloaded = customerRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getChannelLegitimacyStatus()).isFalse();
        assertThat(reloaded.getReasonOfDeny()).isEqualTo(ReasonOfDeny.CUSTOMER_EXPLICIT_STOP);
        assertThat(reloaded.getOriginOfLegitimation()).isNull();

        List<CustomerLegitimationAuditLog> rows = auditRepo.findByCustomerIdOrderByOccurredAtAsc(c.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(CustomerLegitimationEventType.OPT_OUT);
        assertThat(rows.get(0).getToState()).isEqualTo("DENIED");
        assertThat(rows.get(0).getReason()).isEqualTo("CUSTOMER_EXPLICIT_STOP");
        assertThat(rows.get(0).getTriggeredBy()).isEqualTo(SourceActor.CUSTOMER);
    }

    @Test
    void legitimateFromBot_overExplicitOptOut_isSilentNoOp_noNewRow() {
        Customer c = seed("+34600000013", false, ReasonOfDeny.CUSTOMER_EXPLICIT_STOP);

        legitimacyService.legitimateFromBot(c.getId());

        Customer reloaded = customerRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getChannelLegitimacyStatus()).isFalse();
        assertThat(reloaded.getReasonOfDeny()).isEqualTo(ReasonOfDeny.CUSTOMER_EXPLICIT_STOP);
        assertThat(auditRepo.findByCustomerIdOrderByOccurredAtAsc(c.getId())).isEmpty();
    }

    @Test
    void reactivateByCustomer_overExplicitOptOut_persistsReactivated() {
        Customer c = seed("+34600000014", false, ReasonOfDeny.CUSTOMER_EXPLICIT_STOP);

        legitimacyService.reactivateByCustomer(c.getId());

        Customer reloaded = customerRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getChannelLegitimacyStatus()).isTrue();
        assertThat(reloaded.getOriginOfLegitimation()).isEqualTo(OriginOfLegitimation.REACTIVATED_BY_CUSTOMER);

        List<CustomerLegitimationAuditLog> rows = auditRepo.findByCustomerIdOrderByOccurredAtAsc(c.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(CustomerLegitimationEventType.REACTIVATED);
        assertThat(rows.get(0).getOrigin()).isEqualTo("REACTIVATED_BY_CUSTOMER");
        assertThat(rows.get(0).getTriggeredBy()).isEqualTo(SourceActor.CUSTOMER);
    }

    /**
     * End-to-end path: {@code DENIED + NEVER_LEGITIMATED + ATTESTATION}
     * → event type {@code REACTIVATED}, origin {@code ATTESTATION}, triggered by {@code OWNER}.
     */
    @Test
    void attest_overDeniedNever_persistsReactivatedByAttestation_OWNER() {
        Customer c = seed("+34600000015", false, ReasonOfDeny.NEVER_LEGITIMATED);

        legitimacyService.attest(c.getId(), ownerUser.getId(),
                "I have informed the customer and attest to the legal basis");

        Customer reloaded = customerRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getChannelLegitimacyStatus()).isTrue();
        assertThat(reloaded.getOriginOfLegitimation()).isEqualTo(OriginOfLegitimation.ATTESTATION);
        assertThat(reloaded.getReasonOfDeny()).isNull();

        List<CustomerLegitimationAuditLog> rows = auditRepo.findByCustomerIdOrderByOccurredAtAsc(c.getId());
        assertThat(rows).hasSize(1);
        CustomerLegitimationAuditLog row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(CustomerLegitimationEventType.REACTIVATED);
        assertThat(row.getOrigin()).isEqualTo("ATTESTATION");
        assertThat(row.getTriggeredBy()).isEqualTo(SourceActor.OWNER);
        assertThat(row.getActorUserId()).isEqualTo(ownerUser.getId());
        assertThat(row.getFromState()).isEqualTo("DENIED");
        assertThat(row.getToState()).isEqualTo("LEGITIMATE");
        assertThat(row.getAttestationText()).isNotBlank();
    }

    @Test
    void legitimateFromBot_idempotent_singleAuditRow() {
        Customer c = seed("+34600000016", null, null);

        legitimacyService.legitimateFromBot(c.getId());
        legitimacyService.legitimateFromBot(c.getId()); // second call is a no-op (already legitimate)

        assertThat(auditRepo.findByCustomerIdOrderByOccurredAtAsc(c.getId())).hasSize(1);
    }
}
