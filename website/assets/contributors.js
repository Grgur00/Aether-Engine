const guideMenu = document.querySelector('.docs-menu-button');
const guideSidebar = document.querySelector('.docs-sidebar');
guideMenu.addEventListener('click', () => {
  const open = guideSidebar.classList.toggle('open');
  guideMenu.setAttribute('aria-expanded', String(open));
});
document.querySelector('#guide-search').addEventListener('input', (event) => {
  const query = event.target.value.trim().toLowerCase();
  let matches = 0;
  guideSidebar.querySelectorAll('.docs-nav-group a').forEach((link) => {
    link.hidden = !link.textContent.toLowerCase().includes(query);
    if (!link.hidden) matches += 1;
  });
  document.querySelector('.docs-nav-empty').hidden = matches !== 0;
});
document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  guideSidebar.classList.remove('open');
  guideMenu.setAttribute('aria-expanded', 'false');
  guideMenu.focus();
});
