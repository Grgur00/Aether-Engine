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

async function renderDiagrams() {
  if (!window.mermaid) return;
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'neutral',
    fontFamily: 'Arial, sans-serif',
    suppressErrorRendering: true,
  });
  const diagrams = document.querySelectorAll('.docs-diagram');
  for (const [index, figure] of Array.from(diagrams).entries()) {
    const view = figure.querySelector('.docs-diagram-view');
    const source = figure.querySelector('.docs-diagram-source');
    try {
      const { svg, bindFunctions } = await mermaid.render(`guide-diagram-${index}`, source.querySelector('code').textContent);
      view.innerHTML = svg;
      const graphic = view.querySelector('svg');
      graphic.style.width = `${Math.ceil(graphic.viewBox.baseVal.width)}px`;
      graphic.setAttribute('role', 'img');
      graphic.setAttribute('aria-label', 'Architecture and workflow diagram');
      view.hidden = false;
      source.open = false;
      if (bindFunctions) bindFunctions(view);
      figure.dataset.diagramState = 'rendered';
    } catch (error) {
      figure.dataset.diagramState = 'failed';
      console.warn('Unable to render documentation diagram:', error);
    }
  }
}

renderDiagrams();
