const header = document.querySelector("[data-header]");
const menuButton = document.querySelector(".menu-button");
const navigation = document.querySelector(".primary-nav");

const updateHeader = () => header.classList.toggle("scrolled", window.scrollY > 8);
updateHeader();
window.addEventListener("scroll", updateHeader, { passive: true });

menuButton.addEventListener("click", () => {
  const open = navigation.classList.toggle("open");
  menuButton.setAttribute("aria-expanded", String(open));
});

navigation.addEventListener("click", (event) => {
  if (!event.target.closest("a")) return;
  navigation.classList.remove("open");
  menuButton.setAttribute("aria-expanded", "false");
});

document.querySelectorAll(".copy-button").forEach((button) => {
  button.addEventListener("click", async () => {
    const code = button.closest(".code-panel").querySelector("code").innerText;
    try {
      await navigator.clipboard.writeText(code);
      const original = button.textContent;
      button.textContent = "Copied";
      window.setTimeout(() => { button.textContent = original; }, 1400);
    } catch {
      button.textContent = "Select text";
    }
  });
});

const stepLinks = [...document.querySelectorAll(".steps-nav a")];
const steps = [...document.querySelectorAll(".step")];

if ("IntersectionObserver" in window && steps.length) {
  const observer = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((left, right) => right.intersectionRatio - left.intersectionRatio)[0];
      if (!visible) return;
      stepLinks.forEach((link) => {
        link.classList.toggle("active", link.hash === `#${visible.target.id}`);
      });
    },
    { rootMargin: "-25% 0px -55%", threshold: [0, 0.25, 0.5] }
  );
  steps.forEach((step) => observer.observe(step));
  stepLinks[0]?.classList.add("active");
}
