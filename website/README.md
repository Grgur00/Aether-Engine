# Static Website

Open `index.html` directly for a local preview. The contributor handbook is at
`contributors/index.html`; it shares the site's header, styles and navigation.
The existing GitHub Pages workflow publishes `website/` on an authorized push to
`main`. Generating pages locally does not deploy them.

Contributor pages are generated from `Docs/onboarding`, excluding the separate
H2 protocol supplement. Do not edit generated HTML by hand. Update the Markdown,
then regenerate using the pinned Markdown parser:

```powershell
.venv/Scripts/python.exe -m pip install -r env/requirements-docs.lock
.venv/Scripts/python.exe scripts/build-contributor-docs.py
.venv/Scripts/python.exe scripts/check-website-docs.py
```

Run these from the repository root. Commit the source Markdown and generated
pages together. Local onboarding links become website links; code, tests and
other repository links point to GitHub. Build outputs and local research
checkouts are not copied into the site. Fenced architecture sketches retain a
readable source representation without fetching a diagram renderer at runtime.
