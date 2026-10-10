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
  if (document.querySelector('.diagram-dialog[open]')) return;
  guideSidebar.classList.remove('open');
  guideMenu.setAttribute('aria-expanded', 'false');
  guideMenu.focus();
});

const diagramDialog = document.createElement('dialog');
diagramDialog.className = 'diagram-dialog';
diagramDialog.innerHTML = '<header><h2 id="diagram-dialog-title">Diagram</h2><button type="button" class="diagram-close">Close</button></header><div class="diagram-dialog-view" tabindex="0" aria-label="Expanded diagram"></div>';
diagramDialog.setAttribute('aria-labelledby', 'diagram-dialog-title');
document.body.append(diagramDialog);
let expandedDiagram = null;
diagramDialog.querySelector('.diagram-close').addEventListener('click', () => diagramDialog.close());
diagramDialog.addEventListener('click', (event) => {
  if (event.target === diagramDialog) diagramDialog.close();
});
diagramDialog.addEventListener('close', () => {
  if (!expandedDiagram) return;
  const { graphic, view } = expandedDiagram;
  graphic.style.width = `min(100%, ${Math.ceil(graphic.viewBox.baseVal.width)}px)`;
  view.append(graphic);
  expandedDiagram = null;
});

async function renderDiagrams() {
  if (!window.mermaid) return;
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    fontFamily: 'Arial, sans-serif',
    themeVariables: {
      fontSize: '14px',
      primaryColor: '#eff7f2',
      primaryTextColor: '#20362b',
      primaryBorderColor: '#94b3a1',
      secondaryColor: '#edf3fa',
      secondaryTextColor: '#243c56',
      secondaryBorderColor: '#9bb2cc',
      tertiaryColor: '#fff5e5',
      tertiaryTextColor: '#493c28',
      tertiaryBorderColor: '#d7bc85',
      lineColor: '#71877a',
      textColor: '#20362b',
      mainBkg: '#eff7f2',
      nodeBorder: '#94b3a1',
      clusterBkg: '#f8faf9',
      clusterBorder: '#d4e0d9',
      edgeLabelBackground: '#ffffff',
      actorBkg: '#edf3fa',
      actorBorder: '#9bb2cc',
      actorTextColor: '#243c56',
      signalColor: '#71877a',
      signalTextColor: '#20362b',
      noteBkgColor: '#fff5e5',
      noteBorderColor: '#d7bc85',
      noteTextColor: '#493c28',
    },
    flowchart: { nodeSpacing: 24, rankSpacing: 32, padding: 12, curve: 'linear' },
    sequence: { actorMargin: 30, diagramMarginX: 8, diagramMarginY: 8, useMaxWidth: true },
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
      graphic.style.removeProperty('max-width');
      graphic.style.width = `min(100%, ${Math.ceil(graphic.viewBox.baseVal.width)}px)`;
      let heading = figure.previousElementSibling;
      while (heading && !heading.matches('h2, h3')) heading = heading.previousElementSibling;
      const title = heading ? heading.textContent : 'Diagram';
      graphic.setAttribute('role', 'img');
      graphic.setAttribute('aria-label', title);
      view.hidden = false;
      source.open = false;
      const expand = document.createElement('button');
      expand.type = 'button';
      expand.className = 'diagram-expand';
      expand.textContent = 'Expand';
      expand.setAttribute('aria-label', `Expand ${title} diagram`);
      expand.addEventListener('click', () => {
        expandedDiagram = { graphic, view };
        diagramDialog.querySelector('h2').textContent = title;
        graphic.style.width = `${Math.ceil(graphic.viewBox.baseVal.width)}px`;
        diagramDialog.querySelector('.diagram-dialog-view').append(graphic);
        diagramDialog.showModal();
      });
      figure.append(expand);
      if (bindFunctions) bindFunctions(view);
      figure.dataset.diagramState = 'rendered';
    } catch (error) {
      figure.dataset.diagramState = 'failed';
      console.warn('Unable to render documentation diagram:', error);
    }
  }
}

renderDiagrams();
