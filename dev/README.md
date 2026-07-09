# dev/

Local development utilities.

This directory contains tools for setting up and populating a local development environment. It does not contain production code, production configuration, or real data.

---

## Contents

| File | Description |
|---|---|
| `seed_example.sql` | Synthetic seed data for local PostgreSQL population |

---

## What This Directory Is NOT

- Not n8n workflow exports (those contain credentials and are gitignored)
- Not real business data (pilot client names are never committed)
- Not production scripts

---

## Local Setup

*(Detailed setup instructions will be in the root README.md at v0.3.0)*

To seed a local database:

```bash
psql -U postgres -d botmanager_local -f dev/seed_example.sql
```

The seed data creates:
- Two demo businesses (`Clinica Demo`, `Peluquería Demo`)
- Sample employees and schedules
- Sample customers with synthetic contact data
- Sample appointments in various states (for FSM testing)
