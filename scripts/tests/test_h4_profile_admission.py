from h4.profile_admission import summarize


def test_profile_weights_are_sampled_and_missing_stacks_are_safe():
    report = summarize([
        {"type": "jdk.ObjectAllocationSample", "values": {"weight": 100,
         "stackTrace": {"frames": [{"method": {"type": {"name": "io/aetherdb/sstable/SSTableReader"},
                                                  "name": "verify"}}]}}},
        {"type": "jdk.ObjectAllocationSample", "values": {"weight": 20, "stackTrace": None}},
        {"type": "jdk.GCHeapSummary", "values": {"heapUsed": 512}},
    ])
    assert report["sampled_allocation_weight_bytes"] == 120
    assert report["verification_sampled_weight_bytes"] == 100
    assert report["maximum_observed_gc_heap_used_bytes"] == 512
    assert report["primary_h4_eligible"] is False
    assert summarize([])["maximum_observed_gc_heap_used_bytes"] is None
