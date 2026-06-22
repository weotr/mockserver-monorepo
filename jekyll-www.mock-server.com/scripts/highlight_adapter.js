/*
 * Adapter so highlight.js works with the site's several code-block markup
 * conventions without rewriting the code blocks:
 *
 *   <pre class="prettyprint lang-X code"><code class="code">...   (most pages)
 *   <pre><code class="code X">...                                (where/* pages)
 *   <pre class="prettyprint code"><code class="code">...          (no language -> auto-detect)
 *
 * Rules:
 *  - detect the language from a lang-X / language-X class on the <pre> OR <code>,
 *    or from a bare highlight.js language token on the <code> class (e.g. "code python")
 *  - if a language is found, highlight with it
 *  - else, only auto-detect blocks explicitly marked `prettyprint` (what the old
 *    highlighter did) — never touch plain `<pre class="code">` output blocks
 *  - SKIP any block that already contains manual <span> highlighting, so
 *    hand-marked snippets (e.g. XML with <span class="element">) are untouched
 */
(function () {
  var ALIASES = {
    js: 'javascript', sh: 'bash', shell: 'bash', text: 'plaintext', txt: 'plaintext',
    toml: 'ini', 'c#': 'csharp', yml: 'yaml', golang: 'go'
  };
  var IGNORE = { code: 1, inline: 1, prettyprint: 1, hljs: 1, '': 1 };

  function resolve(name) {
    if (!name || !window.hljs) return null;
    name = name.toLowerCase();
    var mapped = ALIASES[name] || name;
    return hljs.getLanguage(mapped) ? mapped : null;
  }

  function detectLang(pre, code) {
    var m = (pre.className + ' ' + code.className).match(/(?:^|\s)(?:lang|language)-([a-z0-9#+.]+)/i);
    if (m) { var r = resolve(m[1]); if (r) return r; }
    var toks = code.className.split(/\s+/);
    for (var i = 0; i < toks.length; i++) {
      if (IGNORE[toks[i].toLowerCase()]) continue;
      var r2 = resolve(toks[i]);
      if (r2) return r2;
    }
    return null;
  }

  function run() {
    if (!window.hljs) return;
    try { hljs.configure({ ignoreUnescapedHTML: true }); } catch (e) {}

    var blocks = document.querySelectorAll('pre code');
    Array.prototype.forEach.call(blocks, function (code) {
      if (code.dataset.hlDone) return;
      if (code.querySelector('span')) { code.dataset.hlDone = '1'; return; } // manual highlighting

      var pre = code.closest('pre');
      if (!pre) return;
      var lang = detectLang(pre, code);
      var prettyprint = pre.classList.contains('prettyprint') ||
        /(?:^|\s)prettyprint(?:\s|$)/.test(code.className);

      if (lang) {
        code.classList.add('language-' + lang);
        try { hljs.highlightElement(code); } catch (e) {}
        code.dataset.hlDone = '1';
      } else if (prettyprint) {
        try { hljs.highlightElement(code); } catch (e) {} // auto-detect, as the old highlighter did
        code.dataset.hlDone = '1';
      }
      // else: no language indicator and not prettyprint -> leave untouched
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }
})();
