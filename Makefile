PYTHON ?= python3
DATASET_CONFIG ?= configs/paper/datasets.json
RESULTS ?= results

.PHONY: artifact-smoke reproduce-oct5k reproduce-primary reproduce-all paper-test paper-figures

artifact-smoke:
	$(PYTHON) scripts/reproduce.py smoke --output $(RESULTS)/smoke

paper-test:
	$(PYTHON) scripts/reproduce.py test

reproduce-oct5k: reproduce-primary

reproduce-primary:
	$(PYTHON) scripts/reproduce.py primary --config $(DATASET_CONFIG) --output $(RESULTS)/primary

reproduce-all:
	$(PYTHON) scripts/reproduce.py all --config $(DATASET_CONFIG) --output $(RESULTS)

paper-figures:
	$(PYTHON) scripts/figures.py --input $(RESULTS)/primary --output $(RESULTS)/figures
