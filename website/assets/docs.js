const docsSidebar = document.querySelector(".docs-sidebar");
const docsMenuButton = document.querySelector(".docs-menu-button");
const docsSearch = document.querySelector("#docs-search");
const docsLinks = [...document.querySelectorAll(".docs-nav-group a")];
const docsSections = [...document.querySelectorAll("[data-doc-title]")];

docsMenuButton?.addEventListener("click", () => {
  const open = docsSidebar.classList.toggle("open");
  docsMenuButton.setAttribute("aria-expanded", String(open));
});

docsSidebar?.addEventListener("click", (event) => {
  if (!event.target.closest("a")) return;
  docsSidebar.classList.remove("open");
  docsMenuButton?.setAttribute("aria-expanded", "false");
});

docsSearch?.addEventListener("input", () => {
  const query = docsSearch.value.trim().toLowerCase();
  let matches = 0;
  document.querySelectorAll(".docs-nav-group").forEach((group) => {
    let groupMatches = 0;
    group.querySelectorAll("a").forEach((link) => {
      const section = document.querySelector(link.hash);
      const searchable = `${link.textContent} ${section?.dataset.docTitle || ""}`.toLowerCase();
      const visible = !query || searchable.includes(query);
      link.hidden = !visible;
      if (visible) groupMatches += 1;
    });
    group.hidden = groupMatches === 0;
    matches += groupMatches;
  });
  const empty = document.querySelector(".docs-nav-empty");
  if (empty) empty.hidden = matches !== 0;
});

document.addEventListener("keydown", (event) => {
  if (event.key === "/" && document.activeElement !== docsSearch) {
    event.preventDefault();
    docsSearch?.focus();
  }
  if (event.key === "Escape") {
    docsSearch?.blur();
    docsSidebar?.classList.remove("open");
    docsMenuButton?.setAttribute("aria-expanded", "false");
  }
});

const toc = document.querySelector("#page-toc");
docsSections.slice(0, 8).forEach((section) => {
  const heading = section.querySelector("h1, h2");
  if (!heading || !toc) return;
  const link = document.createElement("a");
  link.href = `#${section.id}`;
  link.textContent = heading.textContent;
  toc.append(link);
});

if ("IntersectionObserver" in window) {
  const observer = new IntersectionObserver(
    (entries) => {
      const current = entries
        .filter((entry) => entry.isIntersecting)
        .sort((left, right) => right.intersectionRatio - left.intersectionRatio)[0];
      if (!current) return;
      docsLinks.forEach((link) => link.classList.toggle("active", link.hash === `#${current.target.id}`));
    },
    { rootMargin: "-18% 0px -68%", threshold: [0, 0.1] }
  );
  docsSections.forEach((section) => observer.observe(section));
}
