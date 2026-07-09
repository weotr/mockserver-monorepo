/*
 * Left-hand navigation behaviour:
 *   1. Collapsible sections (click heading to expand/collapse).
 *      The current page's section is opened server-side; any sections the user
 *      opens are remembered across page loads via localStorage.
 *   2. Live filter box that matches items across every section, auto-expanding
 *      sections with matches and hiding those without.
 */
(function (window, document) {
    "use strict";

    var STORAGE_KEY = "mockserver.nav.open";
    var menu = document.getElementById("menu");
    if (!menu) {
        return;
    }

    var collapsibles = Array.prototype.slice.call(
        menu.querySelectorAll(".nav-collapsible")
    );

    /* ---- persistence -------------------------------------------------- */

    function readOpenSet() {
        try {
            var raw = window.localStorage.getItem(STORAGE_KEY);
            var parsed = raw ? JSON.parse(raw) : {};
            return (parsed && typeof parsed === "object") ? parsed : {};
        } catch (e) {
            return {};
        }
    }

    function writeOpenSet(set) {
        try {
            window.localStorage.setItem(STORAGE_KEY, JSON.stringify(set));
        } catch (e) {
            /* storage unavailable (private mode, disabled) — ignore */
        }
    }

    function sectionName(group) {
        var toggle = group.querySelector(".nav-toggle");
        return toggle ? toggle.textContent.trim() : "";
    }

    function setOpen(group, open) {
        group.classList.toggle("open", open);
        var toggle = group.querySelector(".nav-toggle");
        if (toggle) {
            toggle.setAttribute("aria-expanded", open ? "true" : "false");
        }
    }

    /* Restore remembered sections (in addition to the current one already
       opened server-side), so returning users keep their expanded groups. */
    var openSet = readOpenSet();
    collapsibles.forEach(function (group) {
        if (openSet[sectionName(group)]) {
            setOpen(group, true);
        }
    });

    /* ---- toggle on click --------------------------------------------- */

    collapsibles.forEach(function (group) {
        var toggle = group.querySelector(".nav-toggle");
        if (!toggle) {
            return;
        }
        toggle.addEventListener("click", function () {
            var willOpen = !group.classList.contains("open");
            setOpen(group, willOpen);
            var set = readOpenSet();
            if (willOpen) {
                set[sectionName(group)] = true;
            } else {
                delete set[sectionName(group)];
            }
            writeOpenSet(set);
        });
    });

    /* ---- live filter -------------------------------------------------- */

    var input = document.getElementById("navFilter");
    var clearBtn = document.getElementById("navFilterClear");
    var noResults = menu.querySelector(".nav-no-results");

    function applyFilter(query) {
        query = (query || "").trim().toLowerCase();

        if (clearBtn) {
            clearBtn.hidden = query.length === 0;
        }

        if (!query) {
            menu.classList.remove("filtering");
            collapsibles.forEach(function (group) {
                group.classList.remove("filter-open", "filter-hide");
                Array.prototype.forEach.call(
                    group.querySelectorAll("li"),
                    function (li) { li.classList.remove("filter-hide"); }
                );
            });
            if (noResults) {
                noResults.hidden = true;
            }
            return;
        }

        menu.classList.add("filtering");
        var anyMatch = false;

        collapsibles.forEach(function (group) {
            var groupMatch = false;
            Array.prototype.forEach.call(
                group.querySelectorAll(".nav-panel li"),
                function (li) {
                    var text = li.textContent.toLowerCase();
                    var hit = text.indexOf(query) !== -1;
                    li.classList.toggle("filter-hide", !hit);
                    if (hit) {
                        groupMatch = true;
                    }
                }
            );
            group.classList.toggle("filter-open", groupMatch);
            group.classList.toggle("filter-hide", !groupMatch);
            if (groupMatch) {
                anyMatch = true;
            }
        });

        if (noResults) {
            noResults.hidden = anyMatch;
        }
    }

    if (input) {
        input.addEventListener("input", function () {
            applyFilter(input.value);
        });
        input.addEventListener("keydown", function (e) {
            if (e.key === "Escape" || e.keyCode === 27) {
                input.value = "";
                applyFilter("");
                input.blur();
            }
        });
    }

    if (clearBtn) {
        clearBtn.addEventListener("click", function () {
            if (input) {
                input.value = "";
                input.focus();
            }
            applyFilter("");
        });
    }

}(this, this.document));
