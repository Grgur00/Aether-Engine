# Sources and manuscript status

Primary source pages inspected during implementation:

- [IEEE Computer Society author resources](https://www.computer.org/publications/author-resources): journal abstract guidance and the standard Transactions overlength threshold. The working target is 12 formatted pages; the page explicitly notes that submission limits may differ. Verify TPDS-specific instructions again before submission.
- [FFCV, CVPR 2023 paper](https://openaccess.thecvf.com/content/CVPR2023/papers/Leclerc_FFCV_Accelerating_Training_by_Removing_Data_Bottlenecks_CVPR_2023_paper.pdf).
- [HyCache, USENIX ATC 2025](https://www.usenix.org/conference/atc25/presentation/jha), including official bibliography.
- [Seneca, arXiv 2511.13724](https://arxiv.org/abs/2511.13724).
- [PyTorch accelerator-specific installation commands](https://pytorch.org/get-started/previous-versions/).
- [SciPy paired log-ratio test building block](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.ttest_1samp.html).
- [statsmodels paired TOST reference](https://www.statsmodels.org/stable/generated/statsmodels.stats.weightstats.ttost_paired.html).

The pasted research report contains transient citation tokens, not reusable bibliography entries. They have not been copied into the manuscript. Dataset references, current TPDS policies, costs, OA coverage and author disclosure wording still require final source review when actual submission material is prepared. The manuscript does not repeat mutable price/quota/accelerator-retirement claims from the report.

`manuscript.tex` and `supplement.tex` are drafts with explicit pending-evidence markers. They are not represented as a compiled 12-page submission. Use an installed current IEEEtran class with `latexmk -pdf manuscript.tex` and compile the supplement separately; record page count and author-policy checks after real figures and tables are imported.
