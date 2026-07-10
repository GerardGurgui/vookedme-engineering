# Publication Pipeline
## vookedme-engineering

**Version:** 1.0  
**Date:** 2026-07-10  
**Status:** Canonical — applies to every Engineering Journey published in this repository

---

## Purpose

This document defines the publication pipeline for all Engineering Journeys in this repository. A Journey is a coherent collection of engineering knowledge organized around a domain or artifact type:

- ADR Journey — Architecture Decision Records
- Architecture Journey — System architecture documentation
- Governance Journey — Domain rules, FSMs, permission matrices
- Engineering Investigations Journey — Deep dives into specific problems
- Case Studies Journey — Cross-cutting narratives from first principles to production
- Source Journey — Annotated source code with architectural context

The pipeline ensures that every Journey is published with the same quality, the same editorial discipline, and the same engineering integrity. It is not a checklist to rush through. It is a quality gate that each Journey must pass before engineering knowledge reaches the public repository.

---

## Pipeline Overview

```
Engineering Journey
        │
        ▼
    1. AUDIT
    Inventory, security scan, quality assessment
        │
        ▼
    2. NORMALIZATION
    Terminology, style, cross-links, structure
        │
        ▼
    3. CLASSIFICATION
    Publication type + editorial value
        │
        ▼
    4. MIGRATION
    Transform each document per its classification
        │
        ▼
    5. QUALITY GATE
    Sanitization checklist, engineering review, peer review
        │
        ▼
    6. PUBLICATION
    Write to public repository
        │
        ▼
    7. RELEASE
    Tag, index update, navigation update
        │
        ▼
    8. POST-PUBLICATION REVIEW
    Broken links, narrative coherence, reader feedback
```

---

## Stage 1 — Audit

**Purpose:** Understand what exists before touching anything. The audit is the foundation for every subsequent decision.

**Inputs:**
- The private source collection (read-only)
- No assumptions about content, quality, or completeness

**Outputs:**
- Complete inventory (every file, its status, its language, its relationship to others)
- Security findings categorized by severity (Critical / Medium / Low)
- Quality findings (inconsistencies, gaps, missing cross-references, structural problems)
- List of missing documents that should exist before the Journey is complete

**Quality gates:**
- Every file in the source collection is accounted for
- Critical security findings are identified and blocked from proceeding
- Missing documents are catalogued with urgency classification

**Exit criteria:**  
An audit document exists that a new engineer could read and understand the entire collection without reading any source file.

---

## Stage 2 — Normalization

**Purpose:** Define the vocabulary and style that will govern the entire Journey. Normalization happens once and benefits all future documents in the Journey.

**Inputs:**
- Audit findings (quality issues, inconsistencies)
- The publication standard document for the Journey type

**Outputs:**
- Updated publication standard (if gaps found in Audit)
- Canonical terminology glossary for the Journey (key terms, preferred spelling, case conventions)
- Cross-reference map (which documents reference which)
- Style decisions documented (voice, tense, level of abstraction)

**Quality gates:**
- Terminology is consistent across all planned documents
- Section naming is consistent
- Status values are consistent
- Cross-reference naming conventions are established

**Exit criteria:**  
Every future migration in this Journey follows the same conventions. A reviewer reading any two documents in the Journey should not be able to tell they were written at different times.

---

## Stage 3 — Classification

**Purpose:** Assign every document two classifications: one for publication handling, one for editorial value. These classifications drive migration effort and repository navigation.

**Inputs:**
- Audit findings
- Normalization output

**Outputs:**
- Classification matrix (one row per document)
- Editorial value classification (FOUNDATIONAL / CORE / ADVANCED / REFERENCE) for each document
- Publication classification (PUBLIC WITH SANITIZATION / WITH REWRITE / WRITE NEW / PRIVATE) for each document
- Batch groupings for migration

**Quality gates:**
- Every document has both a publication and editorial classification
- No document is left unclassified
- PRIVATE documents are explicitly listed and rationale documented

**Exit criteria:**  
Given the classification matrix, a migrating engineer knows exactly what to do with every document without reading the source.

---

## Stage 4 — Migration

**Purpose:** Transform each document according to its classification. This is the core engineering work of the pipeline.

**Migration types and their contracts:**

**PUBLIC WITH SANITIZATION**  
The document's engineering content is preserved. Only references to internal artifacts are removed or replaced with abstract descriptions. The output is the source document with targeted removals — not a rewrite.

Checklist: Apply all applicable sanitization rules from the publication standard. Run the sanitization grep. Verify Engineering Principle section exists. Verify editorial classification is set.

**PUBLIC WITH REWRITE**  
The document's engineering insight is preserved; the document itself is replaced. Read the source for its engineering value, then write a new document that communicates that value without any operational, internal, or sensitive content. The source is reference material, not a template.

Checklist: The new document must be entirely original prose. No sentences copied from the source. The engineering insight — the reason the source ADR existed — must be present and elevated. Run sanitization grep to confirm zero internal references.

**WRITE NEW**  
No source document exists. Write the document from first principles, using the source code and architecture as reference. This is new engineering knowledge being made explicit for the first time.

Checklist: Identify the engineering question this document answers. Describe the alternatives that were available. Explain the decision. Write the transferable engineering principle.

**PRIVATE**  
Do not migrate. Document the rationale in the classification matrix. If the document contains engineering knowledge that should eventually be public, create a new document that expresses that knowledge without the private content.

**Inputs:**
- Source document (for SANITIZATION and REWRITE types)
- Classification matrix
- Normalization decisions

**Outputs:**
- Migrated documents in a temporary location (not yet in the public repository)
- Migration notes for any decision made during the migration that affects other documents

**Quality gates:**
- Each migrated document is complete — all mandatory sections present
- Engineering Principle section exists and is transferable
- Engineering Question Answered field is present
- Editorial classification is set

**Exit criteria:**  
All documents in the current batch are migrated and complete in a temporary location, ready for the quality gate.

---

## Stage 5 — Quality Gate

**Purpose:** Verify that no document contains sensitive information, that engineering principles are genuinely transferable, and that the batch coheres as a unit.

**Inputs:**
- All migrated documents from Stage 4

**Process:**

**Sanitization scan (automated):**
```bash
grep -ri "AGENTS\|AUDIT_REPORT\|DECISION_LOG\|IMPLEMENTATION_ROADMAP\|PHASE_B\|cita id\|com/botmanager\|CRIT-\|HIGH-\|B-10\|B-11\|B-16\|DPA_BETA\|TOS_BETA\|PRIVACIDAD_BETA\|bot-panel v2\|FASE 1\|FASE 2\|Sprint" <document>
```
Must return zero matches.

**Engineering review (manual):**
For each document, ask:
- Would this ADR still be valuable if the system it describes were discontinued tomorrow?
- Could a senior engineer at a different company use this to make a better decision?
- Is the Engineering Principle genuinely transferable, or is it restating the implementation?

If the answer to any of these is "no" or "not clearly," the document goes back to Stage 4.

**Consistency review:**
- Same terminology across all documents in the batch
- Cross-references are accurate
- Batch coheres as a unit — documents feel like they were written together
- Reading order within the batch is sensible

**Outputs:**
- PASS: documents proceed to Stage 6
- FAIL: documents return to Stage 4 with specific feedback

**Exit criteria:**  
Every document in the batch passes the sanitization scan and the engineering review.

---

## Stage 6 — Publication

**Purpose:** Write documents to the public repository.

**Inputs:**
- Approved documents from Stage 5

**Process:**
1. Create the target file in the public repository
2. Copy the approved document content
3. Verify all internal links resolve correctly
4. Update any cross-references that depend on exact file paths

**Outputs:**
- Published files in the public repository
- Updated cross-references

**Quality gates:**
- No broken links in published documents
- File names follow kebab-case convention
- Files are in the correct directory

**Exit criteria:**  
All documents in the batch are in the public repository with correct content and working links.

---

## Stage 7 — Release

**Purpose:** Make the publication visible and navigable.

**Inputs:**
- Published files from Stage 6

**Process:**
1. Update the Journey's index document (e.g. `docs/adr/README.md`)
2. Update the documentation root (`docs/README.md`) if navigation or reading order changes
3. Update the repository README if the batch represents a milestone
4. Create a git commit with the message format: `docs(<journey>): publish <batch-name> — <summary>`
5. Tag the release if the batch meets a minor version milestone

**Commit message format:**
```
docs(adr): publish Batch 1 — Domain Foundation

ADR-011: Appointment Temporal Boundary (PFT) [FOUNDATIONAL]
ADR-001: Single Money Field Invariant [CORE]
ADR-007: Derive Bot State from Source of Truth [CORE]
```

**Outputs:**
- Updated indexes
- Git commit
- Version tag (if milestone)

**Exit criteria:**  
A new visitor to the repository can discover and navigate to every published document without knowing it exists.

---

## Stage 8 — Post-publication Review

**Purpose:** Verify the publication reads correctly in the actual repository context, and capture learning for future batches.

**Inputs:**
- Published and released content

**Process:**
1. Read every published document in the context of the public repository (not in an editor)
2. Follow every cross-reference link
3. Read the updated index and verify reading order is correct
4. Verify editorial value guides the navigation as intended
5. Document what worked well and what should be improved for the next batch

**Outputs:**
- Broken link report (if any; fix immediately)
- Batch retrospective: what changed during migration from what was planned in the audit
- Recommendations for the next batch

**Exit criteria:**  
Every link in every published document resolves. The repository reads coherently from the perspective of a first-time visitor.

---

## Journey Completion Criteria

A Journey is considered complete when:

1. All FOUNDATIONAL and CORE documents have been published
2. All ADVANCED and REFERENCE documents have been published or have explicit deferral rationale
3. The index is complete and accurate
4. The repository README reflects the published content
5. A post-publication review has been completed for the final batch

A Journey may be published incrementally across multiple releases. Completion does not require all documents to be published simultaneously.

---

## Applying This Pipeline to Other Journeys

The pipeline is journey-agnostic. The artifacts change; the stages do not.

| Journey | Primary Audit Concern | Primary Migration Type |
|---|---|---|
| ADR Journey | Security (internal references, production data) | Sanitization + Rewrite |
| Architecture Journey | Completeness (does the diagram match reality?) | Sanitization + Update |
| Governance Journey | Accuracy (do the FSMs match the code?) | Sanitization + Verification |
| Engineering Investigations | Disclosure (does this reveal internals?) | Rewrite |
| Case Studies | Scope (is the narrative self-contained?) | Rewrite |
| Source Journey | Security (credentials, keys, private identifiers) | Sanitization + Annotation |

Each Journey should produce its own audit document, classification matrix, and migration notes before migration begins. The pipeline stages are the same. The specific quality gates and checklist items are Journey-specific.
