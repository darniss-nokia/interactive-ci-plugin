# Licensing

This document explains **what a software license is for**, the **main families** of open-source
licenses, and **why this project uses MIT**. It's written as a primer because the maintainer asked
"how many types of license are there, and which do you suggest?"

---

## Why a license at all?

Without a license, code is "all rights reserved" by default — nobody may legally use, copy, modify,
or redistribute it. An open-source license grants those rights under conditions. Choosing one is a
one-time, high-leverage decision: it sets who can use your work and what they owe you back.

---

## The families of licenses

There are dozens of individual licenses, but they cluster into a handful of **families**. Below,
ordered from most permissive to most restrictive.

### 1. Public domain / near-public-domain
You give up (nearly) all rights. Anyone can do anything, including relicensing.
- **Examples:** Unlicense, CC0, 0BSD, WTFPL.
- **Use when:** you want zero friction and no attribution requirement.
- **Caveat:** "public domain" isn't a clean legal concept in every country, which is why CC0/0BSD exist.

### 2. Permissive (most popular for libraries, tools, and plugins)
Do almost anything, including using the code in closed-source/commercial products. The main
obligation is **keep the copyright + license notice**. No obligation to open-source your changes.
- **Examples:** **MIT**, **BSD-2-Clause**, **BSD-3-Clause**, **Apache-2.0**, ISC.
- **Apache-2.0** additionally includes an **explicit patent grant** and a patent-retaliation clause — valuable for larger/corporate projects.
- **Use when:** you want maximum adoption and don't need to force downstream code open.

### 3. Weak copyleft (file/library-level share-alike)
Modifications **to the licensed files/library** must stay open under the same license, but you may
link it into a larger proprietary program without opening the whole program.
- **Examples:** **LGPL-2.1/3.0**, **MPL-2.0** (Mozilla), **EPL-2.0** (Eclipse), CDDL.
- **Use when:** you want improvements to your component contributed back, but still allow proprietary use around it.

### 4. Strong copyleft (whole-program share-alike)
If you distribute a program that includes this code, the **entire combined work** must be released
under the same license (source included).
- **Examples:** **GPL-2.0**, **GPL-3.0** (GPL-3 adds patent + anti-tivoization terms).
- **Use when:** you want to guarantee the software and its derivatives always stay free/open.

### 5. Network copyleft (closes the "SaaS loophole")
Like strong copyleft, but the share-alike obligation also triggers when users interact with the
software **over a network** (not just when binaries are distributed).
- **Examples:** **AGPL-3.0**.
- **Use when:** the software is typically run as a hosted service and you still want source shared.

### 6. Source-available / non-OSS (for completeness — *not* open source)
Source is visible but with commercial restrictions; these are **not** OSI-approved open source.
- **Examples:** BSL 1.1 (Business Source License), SSPL, Elastic License, "fair-source", Commons Clause.
- **Use when:** you want to publish source but block competitors from reselling it as a service.

### Documentation/asset licenses (adjacent)
Not for code, but you'll see them for docs, images, and data.
- **Examples:** Creative Commons (CC-BY, CC-BY-SA, CC0).

---

## Quick comparison

| Family | Can be used in closed-source? | Must publish changes? | Patent grant | Example |
|---|---|---|---|---|
| Public domain | ✅ | ❌ | ❌ | Unlicense, CC0 |
| **Permissive** | ✅ | ❌ (keep notice) | Apache-2.0 only | **MIT**, BSD, Apache-2.0 |
| Weak copyleft | ✅ (around it) | ✅ (the component) | varies | LGPL, MPL-2.0, EPL-2.0 |
| Strong copyleft | ⚠️ combined work opens | ✅ (whole program) | GPL-3 | GPL-2.0/3.0 |
| Network copyleft | ⚠️ | ✅ (incl. SaaS) | ✅ | AGPL-3.0 |
| Source-available | ⚠️ restricted | varies | varies | BSL, SSPL |

---

## Recommendation for this project: **MIT**

**Decision: MIT.** Confidence: **HIGH.**

**Reasoning (evidence-based):**

1. **Jenkins ecosystem convention.** Jenkins core and the overwhelming majority of plugins on the
   Update Center are **MIT-licensed**. Publishing under MIT means zero license-compatibility
   friction with the platform and its dependencies, and it's what reviewers expect when your plugin
   is hosted under the `jenkinsci` GitHub org.
2. **Maximum adoption.** A HITL/approval surface is infrastructure — its value grows with adoption.
   A permissive license lets any team (including commercial/closed shops) install and extend it
   without legal review.
3. **Dependency compatibility.** Our only bundled third-party runtime library, `commonmark`, is
   BSD-2-Clause (permissive) — fully compatible with MIT. No copyleft is bundled, so an MIT release
   creates no obligations we can't meet.
4. **Simplicity.** MIT is ~170 words, universally understood, and OSI-approved. Lower friction than
   Apache-2.0 while still permissive.

**When we'd pick differently:**

- **Apache-2.0** if we wanted an explicit **patent grant** (sensible if a large corporation is the
  primary author and patent exposure is a concern). It's the main alternative worth considering and
  is still permissive; the trade-off is a longer license and a `NOTICE` file to maintain.
- **EPL-2.0** only if we needed to match a specific Eclipse-aligned codebase.
- **GPL/AGPL** if the explicit goal were to force all downstream forks to stay open — the opposite
  of the adoption-maximizing goal here, so **not** recommended for a plugin.

**Not recommended here:** GPL/AGPL (kills adoption for a plugin), BSL/SSPL (not OSS; can't publish to
the Update Center as open source).

---

## What "MIT" obligates you (the user) to do

Almost nothing: keep the `LICENSE` file / copyright notice when you redistribute. That's it. You may
use it commercially, modify it, and ship it in closed products.

The project's license text lives in [`../LICENSE`](../LICENSE):

```
MIT License
Copyright (c) 2026 Darniss <darniss.mail@gmail.com>
```

> This document is an educational overview, not legal advice. For a binding decision in a commercial
> context, consult your organization's legal team.
