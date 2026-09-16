PYTHON ?= python3
DATASET_CONFIG ?= configs/paper/datasets.json
RESULTS ?= results
SCRATCH_ROOT ?=
SCRATCH_ARGS = $(if $(SCRATCH_ROOT),--scratch-root "$(SCRATCH_ROOT)",)

.PHONY: artifact-smoke reproduce-oct5k reproduce-primary reproduce-all paper-test paper-figures paper-pdf

artifact-smoke:
	$(PYTHON) scripts/reproduce.py smoke --output $(RESULTS)/smoke $(SCRATCH_ARGS)

paper-test:
	$(PYTHON) scripts/reproduce.py test

reproduce-oct5k: reproduce-primary

reproduce-primary:
	$(PYTHON) scripts/reproduce.py primary --config $(DATASET_CONFIG) --output $(RESULTS)/primary $(SCRATCH_ARGS)

reproduce-all:
	$(PYTHON) scripts/reproduce.py all --config $(DATASET_CONFIG) --output $(RESULTS) $(SCRATCH_ARGS)

paper-figures:
	$(PYTHON) scripts/figures.py --input $(RESULTS)/primary --output $(RESULTS)/figures

paper-pdf:
	$(PYTHON) scripts/build_paper.py
