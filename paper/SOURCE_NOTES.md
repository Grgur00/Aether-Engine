# Dataset citation verification

Checked on 2026-09-08. These sources identify the datasets; they do not establish which release or subset was measured by Aether.

- `oct5k`: the published Scientific Data article is volume 12, article 267 (2025), DOI `10.1038/s41597-024-04259-z`. The publisher record and authors' institutional repository describe 1672 manually annotated scans. The bibliography uses the published article rather than the 2023 preprint. [Publisher record](https://doi.org/10.1038/s41597-024-04259-z), [UCL author repository](https://discovery.ucl.ac.uk/id/eprint/10201383/).
- `coco`: the published ECCV 2014 chapter spans pages 740–755, DOI `10.1007/978-3-319-10602-1_48`. The bibliography follows that published version's author list. [Publisher record](https://doi.org/10.1007/978-3-319-10602-1_48), [author laboratory bibliography](https://vision.ics.uci.edu/papers/microsoft-coco-common-objects-in-context-2014/).
- `imagenet`: the official ILSVRC site requests the 2015 IJCV challenge paper. Its author-provided BibTeX gives volume 115, issue 3, pages 211–252 and DOI `10.1007/s11263-015-0816-y`. [Official citation instructions](https://www.image-net.org/challenges/LSVRC/), [author-provided BibTeX](https://ai.stanford.edu/~olga/bibtex/ILSVRC15.bib).

Actual dataset releases, annotation selections, source licenses, class mappings and selected sample counts must be recorded from the remote manifests. The COCO adapter's image-level presence labels and ImageNet adapter's class labels describe the implemented systems workload, not reproduced benchmark accuracy claims. Bibliographic key resolution was checked locally; TeX compilation remains pending.
