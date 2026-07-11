package com.vookedme.botmanager.customer;

import com.vookedme.botmanager.appointment.entity.Appointment;
import com.vookedme.botmanager.appointment.entity.AppointmentSource;
import com.vookedme.botmanager.appointment.entity.AppointmentStatus;
import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.customer.repository.CustomerRepository;
import com.vookedme.botmanager.notification.outbound.BotOutboundNotificationService; // published in a subsequent source batch
import com.vookedme.botmanager.notification.outbound.WhatsAppOutboundPort;           // published in a subsequent source batch
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * End-to-end enforcement test for the outbound legitimacy gate with the feature flag
 * enabled against a real PostgreSQL instance. Verifies that the gate actually suppresses
 * message dispatch when channel legitimacy is absent, and that it correctly allows dispatch
 * when legitimacy is established.
 *
 * <p>The test covers the primary outbound notification path
 * ({@code BotOutboundNotificationService.send}):
 * <ul>
 *   <li><b>Fresh-read:</b> a {@link Customer} entity loaded as legitimate
 *       ({@code channelLegitimacyStatus = true}) whose opt-out is committed in a separate
 *       transaction before the {@code @Async} dispatch fires — the gate re-reads fresh by
 *       {@code customerId} and suppresses the send (does not trust the stale entity).</li>
 *   <li>{@code channelLegitimacyStatus = null} → suppressed (default-deny, Art. 25.2).</li>
 *   <li>{@code channelLegitimacyStatus = true} → sent — also exercises the real
 *       {@code @ConfigurationProperties} binding of {@code app.legitimation.gate-enabled}
 *       (not a mock).</li>
 * </ul>
 *
 * <p>Extends {@link BaseConcurrencyIntegrationTest} (not {@code @Transactional}) because the
 * fresh-read property requires committed state visible across transactions — the async dispatch
 * runs on a separate thread/connection and sees only committed data.
 * {@code WhatsAppOutboundPort} is mocked to assert invocation or suppression.
 *
 * <p>{@code BaseConcurrencyIntegrationTest}, {@code BotOutboundNotificationService}, and
 * {@code WhatsAppOutboundPort} are published in a subsequent source batch.
 */
@TestPropertySource(properties = "app.legitimation.gate-enabled=true")
@DisplayName("Gate enforcement end-to-end: fresh-read suppresses stale legitimacy state")
class LegitimationEnforcementE2EIT extends BaseConcurrencyIntegrationTest {

    private static final String WA_INSTANCE = "wa-test-c1";

    @MockitoBean
    private WhatsAppOutboundPort outboundPort;

    @Autowired
    private BotOutboundNotificationService botOutbound;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void setWhatsappInstance() {
        // send() reads apt.getBusiness().getWhatsappInstance(); set in memory for the gate path.
        testBusiness.setWhatsappInstance(WA_INSTANCE);
    }

    @Test
    @DisplayName("Fresh-read: opt-out committed after entity load suppresses async dispatch")
    void freshRead_committedOptOut_suppressesDispatch() {
        // Entity loaded as legitimate (channelLegitimacyStatus = true) — the 'enqueue snapshot'.
        Customer stale = customerRepository.save(Customer.builder()
                .business(testBusiness).phone("+34600000401").name("FreshReadTest")
                .channelLegitimacyStatus(true).build());
        Appointment apt = inMemoryAppointment(stale);

        // Opt-out committed in a separate transaction (JDBC auto-commit), AFTER the entity was loaded.
        jdbc.update(
                "UPDATE customers SET channel_legitimacy_status = false, "
                        + "reason_of_deny = 'CUSTOMER_EXPLICIT_STOP' WHERE id = ?",
                stale.getId());

        botOutbound.notifyExpired(apt); // @Async → send() → gate.evaluate(id) performs fresh-read

        // Even though apt.getCustomer() carries channelLegitimacyStatus=true, the gate
        // re-reads fresh (sees false) → suppresses the send.
        verify(outboundPort, after(1200).never()).sendMessage(any(), any(), any());
    }

    @Test
    @DisplayName("Channel legitimacy null (unevaluated) → message suppressed (default-deny)")
    void canalNull_suppressed() {
        Customer c = customerRepository.save(Customer.builder()
                .business(testBusiness).phone("+34600000402").name("NullLegitimacy")
                .channelLegitimacyStatus(null).build());

        botOutbound.notifyExpired(inMemoryAppointment(c));

        verify(outboundPort, after(1200).never()).sendMessage(any(), any(), any());
    }

    @Test
    @DisplayName("Channel legitimacy true → message sent (gate allows; feature flag binding verified)")
    void canalTrue_sends() {
        Customer c = customerRepository.save(Customer.builder()
                .business(testBusiness).phone("+34600000403").name("LegitimateCustomer")
                .channelLegitimacyStatus(true).build());

        botOutbound.notifyExpired(inMemoryAppointment(c));

        verify(outboundPort, timeout(4000)).sendMessage(eq(WA_INSTANCE), eq("+34600000403"), any());
    }

    /** In-memory appointment (not persisted) with the given business (whatsappInstance) and customer. */
    private Appointment inMemoryAppointment(Customer customer) {
        return Appointment.builder()
                .business(testBusiness).customer(customer)
                .source(AppointmentSource.BOT).status(AppointmentStatus.CONFIRMED)
                .datetime(LocalDateTime.now().plusDays(1)).durationMinutes(30).build();
    }
}
