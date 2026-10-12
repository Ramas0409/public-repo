-- Office step 23: the network table is dropped (POC db/V46, CL-185); a network's display name is code
-- (model.NetworkNames). Run after the office orchestrator carries the CL-185 RegisterRows. Safe to run again.
DROP TABLE IF EXISTS wdp_ai.network;
