# Interactive View — Markdown rendering showcase

This file exercises **every markdown construct** the Interactive View renders, so the
`interactiveView` viewer (and the `askInteractive` modal context panel) can be verified end to end.

It focuses on the constructs that come from the enabled **GitHub-Flavored Markdown** extensions —
**tables**, **strikethrough** and **autolinks** — alongside the CommonMark core.

---

## 1. Headings

# H1 heading
## H2 heading
### H3 heading
#### H4 heading
##### H5 heading
###### H6 heading

## 2. Inline emphasis

- **Bold** with `**` and __bold__ with `__`
- *Italic* with `*` and _italic_ with `_`
- ***Bold italic***
- ~~Strikethrough~~ (GFM `~~...~~` → `<del>`)
- `inline code` with backticks
- Escaped literal asterisks: \*not italic\*
- A hard line break at the end of this line&#x20;
  continues on the next rendered line.

## 3. Blockquotes

> A single-level blockquote.
>
> > A nested blockquote inside it.
> >
> > > Three levels deep, with **bold** and `code`.

## 4. Lists

Unordered:

- First item
- Second item
  - Nested item A
  - Nested item B
    - Deeper still
- Third item

Ordered:

1. Step one
2. Step two
   1. Sub-step 2a
   2. Sub-step 2b
3. Step three

Task list (note: the task-list extension is **not** bundled, so these render as plain list items
with a literal `[ ]` / `[x]` — included here to document that known limitation):

- [ ] Unchecked task
- [x] Checked task

A list item containing a fenced code block:

1. Run the build:

   ```bash
   mvn -B -ntp clean verify
   ```

2. Then deploy.

## 5. Links, autolinks and images

- Inline link: [Jenkins design library](https://weekly.ci.jenkins.io/design-library/)
- Reference link: [CommonMark spec][cm]
- Bare URL (autolink): https://www.jenkins.io/
- E-mail autolink: mailto is recognised, e.g. dev@example.com
- Image: ![Jenkins logo](https://www.jenkins.io/images/logos/jenkins/jenkins.svg)

[cm]: https://spec.commonmark.org/

## 6. Code blocks

Fenced with a language (rendered as an escaped code block):

```java
public static String render(String markdown) {
    return RENDERER.render(PARSER.parse(markdown));
}
```

Indented code block:

    $ curl -s http://localhost:8080/interactive-input/api/v1/health
    {"status":"ok","pending":0}

## 7. Horizontal rule

Above the rule.

---

Below the rule.

## 8. Tables (the primary fix)

### 8.1 Simple table

| Aspect | Supported values / matrix | Source |
|---|---|---|
| `fingerprint` length | changed from `18..255` → `1..255` (octets) | design §Management Model |
| Wildcard value | first octet `FF` = wildcard (matches any cert) | design §Approach |
| Hash identifiers | `01`=md5, `02`=sha1, `04`=sha256, `FF`=wildcard | light-analysis §Behavior |

### 8.2 Column alignment (`:--` left, `:-:` center, `--:` right)

| Left | Center | Right |
|:-----|:------:|------:|
| a | b | 1 |
| longer cell | mid | 1000 |
| x | y | 42 |

### 8.3 Inline formatting inside cells

| Feature | Example | Status |
|---|---|---|
| Bold + code | **`retryCount`** must be `>= 0` | ~~deprecated~~ **active** |
| Link in a cell | see [the docs](https://www.jenkins.io/) | OK |
| Pipes escaped in a cell | `a \| b` renders literally | OK |

## 9. Inline HTML is escaped (never executed)

The following is shown as literal text, not run:

<script>alert('xss')</script>

And an entity renders as a symbol: &copy; 2026, 5 &lt; 10.

## 10. Combined / nested

> **Note:** the table below sits inside a blockquote-adjacent section and mixes constructs.

1. A step with a table of results:

| Test | Result |
|---|---|
| unit | **passed** |
| integration | ~~flaky~~ passed |

2. A final paragraph with a bare link https://plugins.jenkins.io/interactive-ci/ and some
   `inline code`, *emphasis*, and a footnote-style reference to the design library above.
