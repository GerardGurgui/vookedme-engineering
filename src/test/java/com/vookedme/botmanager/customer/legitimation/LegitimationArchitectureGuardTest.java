package com.vookedme.botmanager.customer.legitimation;

import com.vookedme.botmanager.customer.entity.Customer;
import com.vookedme.botmanager.customer.entity.CustomerLegitimationAuditLog;
import com.vookedme.botmanager.customer.entity.OriginOfLegitimation;
import com.vookedme.botmanager.customer.entity.ReasonOfDeny;
import com.vookedme.botmanager.customer.repository.CustomerLegitimationAuditLogRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.time.OffsetDateTime;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guards for the customer legitimation invariants — enforced by static
 * bytecode analysis (ArchUnit) so that a future regression fails the build rather
 * than requiring a runtime test to detect it.
 *
 * <ul>
 *   <li><b>G-WRITE-OWNER</b> — no class outside {@code customer.legitimation} may mutate
 *       the four legitimation fields on {@link Customer} (single-write-owner). Bypassing the
 *       writer would bypass the atomicity guarantee (audit row in same transaction), the domain
 *       invariant, the customer precedence rule, and the {@code @Version} retry.</li>
 *   <li><b>G-AUDIT-EMITTER</b> — only {@code customer.legitimation} may construct and
 *       persist {@link CustomerLegitimationAuditLog}. The construction rule is the
 *       comprehensive guard: it is impossible to persist what cannot be constructed.</li>
 *   <li><b>G-WRITER-ENCAPSULATION</b> — the package-private writer may only be accessed
 *       within its package (orchestrated by the service with the {@code @Version} retry);
 *       guards against promoting the writer to {@code public} and bypassing the retry.</li>
 * </ul>
 *
 * <p><b>Scope:</b> {@code @AnalyzeClasses} with {@link ImportOption.DoNotIncludeTests} analyses
 * {@code src/main} only — test fixtures use the setters legitimately. No MapStruct or generated
 * mappers are present; CGLIB proxies are runtime-only (not in compiled output).
 *
 * <p><b>Accepted limitations:</b>
 * <ul>
 *   <li>Construction rules exclude {@code customer.entity} (necessary because the Lombok-generated
 *       builder resides there). A class placed inside {@code customer.entity} could construct
 *       Customer or the audit log without the guard detecting it — accepted: {@code customer.entity}
 *       is the entity package and contains no business logic.</li>
 *   <li>Rules reference field and method names by string (e.g. {@code setChannelLegitimacyStatus}).
 *       Renaming a legitimation field would make the affected rule vacuous without a build failure —
 *       it would stop protecting rather than fail. Renaming a legitimation field is a major change
 *       that must also update this guard.</li>
 *   <li>Raw SQL, {@code @Modifying @Query}, or native queries over the legitimation columns
 *       are not visible to ArchUnit (bytecode); these are covered at the data layer by the
 *       append-only database trigger and schema CHECK constraints.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.vookedme.botmanager", importOptions = ImportOption.DoNotIncludeTests.class)
class LegitimationArchitectureGuardTest {

    private static final String LEGIT = "..customer.legitimation..";
    private static final String ENTITY = "..customer.entity..";

    /**
     * Any {@link Customer} constructor with arguments (the {@code @AllArgsConstructor}
     * writes the four legitimation fields via {@code putfield}, bypassing setters). The
     * no-arg constructor writes no fields and is excluded to avoid a false positive on a
     * legitimate {@code new Customer()}. Robust against adding fields to Customer (does
     * not assert exact arity).
     */
    private static final DescribedPredicate<JavaConstructorCall> PARAMETERIZED_CUSTOMER_CTOR =
            new DescribedPredicate<>("a parameterized Customer constructor (all-args)") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    return call.getTarget().getOwner().isEquivalentTo(Customer.class)
                            && !call.getTarget().getRawParameterTypes().isEmpty();
                }
            };

    // ── G-WRITE-OWNER ─────────────────────────────────────────────────────────
    // Closes the full write surface of the four legitimation fields: (a) the four setters,
    // (b) the four Lombok builder fluent methods, and (c) the all-args constructor.
    // (b) and (c) write via putfield directly (without the setter), so a guard on setters
    // alone would leave a false negative: Customer.builder().channelLegitimacyStatus(true)...build()
    // would pass green.

    @ArchTest
    static final ArchRule g_h2_write_owner = noClasses()
            .that().resideOutsideOfPackage(LEGIT)
            .should().callMethod(Customer.class, "setChannelLegitimacyStatus", Boolean.class)
            .orShould().callMethod(Customer.class, "setReasonOfDeny", ReasonOfDeny.class)
            .orShould().callMethod(Customer.class, "setOriginOfLegitimation", OriginOfLegitimation.class)
            .orShould().callMethod(Customer.class, "setLegitimatedAt", OffsetDateTime.class)
            .as("G-WRITE-OWNER (setters)")
            .because("only the customer.legitimation package (LegitimacyTransactionalWriter) may mutate "
                    + "the four legitimation fields on Customer via setter");

    @ArchTest
    static final ArchRule g_h2_write_owner_construction = noClasses()
            .that().resideOutsideOfPackage(LEGIT).and().resideOutsideOfPackage(ENTITY)
            .should().callMethod(Customer.CustomerBuilder.class, "channelLegitimacyStatus", Boolean.class)
            .orShould().callMethod(Customer.CustomerBuilder.class, "reasonOfDeny", ReasonOfDeny.class)
            .orShould().callMethod(Customer.CustomerBuilder.class, "originOfLegitimation", OriginOfLegitimation.class)
            .orShould().callMethod(Customer.CustomerBuilder.class, "legitimatedAt", OffsetDateTime.class)
            .orShould().callConstructorWhere(PARAMETERIZED_CUSTOMER_CTOR)
            .as("G-WRITE-OWNER (construction)")
            .because("the four legitimation fields are also written by the Lombok builder and the all-args constructor "
                    + "(putfield directly, without setter); only customer.legitimation may do this. "
                    + "customer.entity is excluded because the Lombok-generated builder constructor resides there");

    @ArchTest
    static final ArchRule g_audit_emitter_construction = noClasses()
            .that().resideOutsideOfPackage(LEGIT).and().resideOutsideOfPackage(ENTITY)
            .should().callConstructorWhere(JavaCall.Predicates.target(owner(equivalentTo(CustomerLegitimationAuditLog.class))))
            .orShould().callMethod(CustomerLegitimationAuditLog.class, "builder")
            .as("G-AUDIT-EMITTER (construction)")
            .because("only customer.legitimation may construct legitimation audit rows (new + builder); "
                    + "since you cannot persist what you cannot construct, this covers all write paths "
                    + "(repo.save and EntityManager). customer.entity is excluded for the Lombok-generated builder");

    @ArchTest
    static final ArchRule g_audit_emitter_persist = noClasses()
            .that().resideOutsideOfPackage(LEGIT)
            .should().callMethodWhere(
                    JavaCall.Predicates.target(name("save").or(name("saveAll")).or(name("saveAndFlush")))
                            .and(JavaCall.Predicates.target(owner(assignableTo(CustomerLegitimationAuditLogRepository.class)))))
            .as("G-AUDIT-EMITTER (persistence)")
            .because("only customer.legitimation may persist legitimation audit rows via the repository "
                    + "(defence-in-depth over the construction guard)");

    @ArchTest
    static final ArchRule g_writer_encapsulation = classes()
            .that().areAssignableTo(LegitimacyTransactionalWriter.class)
            .should().onlyBeAccessed().byClassesThat().resideInAPackage(LEGIT)
            .as("G-WRITER-ENCAPSULATION")
            .because("the package-private writer (orchestrated by the service with the @Version retry) "
                    + "must only be accessed within customer.legitimation");
}
   