package com.vcfcf.adapters.unifi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Build 15 (issue #21): the bulk properties request paths and the vmnic
 * LLDP extraction (unchanged from build 9, now SDK-free). Run from the repo
 * root (ci/run_java_tests.sh).
 */
public final class VmnicLldpTest {

	public static void main(String[] args) {
		bulkPaths();
		extraction();
		System.out.println("VmnicLldpTest: all assertions passed");
	}

	static void bulkPaths() {
		T.eq(0, VmnicLldp.bulkPropertiesPaths(Collections.<String>emptyList(), 50).size(),
				"no hosts, no request");
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 120; i++) ids.add(String.format("id-%03d", i));
		List<String> paths = VmnicLldp.bulkPropertiesPaths(ids, 50);
		T.eq(3, paths.size(), "120 hosts in batches of 50: 3 requests, not 120");
		T.check(paths.get(0).startsWith(
				"/api/resources/properties?resourceId=id-000&resourceId=id-001&"),
				paths.get(0));
		T.eq(50, paths.get(0).split("resourceId=", -1).length - 1, "batch 1 size");
		T.eq(20, paths.get(2).split("resourceId=", -1).length - 1, "last batch size");
		List<List<String>> batches = VmnicLldp.batches(ids, 50);
		T.eq(3, batches.size(), "batches match paths");
		T.eq("id-100", batches.get(2).get(0), "batch order follows the host order");
		T.eq("/api/resources/properties?resourceId=a%2Fb%26c",
				VmnicLldp.bulkPropertiesPaths(Arrays.asList("a/b&c"), 50).get(0),
				"ids are encoded");
	}

	static void extraction() {
		List<String[]> props = Arrays.asList(
				new String[]{"net:vmnic0|discoveryProtocol|lldp|systemName", "usw-xg-8"},
				new String[]{"net:vmnic0|discoveryProtocol|lldp|portName", "Port 7"},
				new String[]{"net:vmnic1|discoveryProtocol|lldp|systemName", "usw-lite"},
				new String[]{"net:vmnic2|discoveryProtocol|cdp|systemName", "cisco"},
				new String[]{"net:vmnic2|discoveryProtocol|cdp|portName", "Gi0/1"},
				new String[]{"net:vmnic3|discoveryProtocol|lldp|systemName", ""},
				new String[]{"net:vmnic3|discoveryProtocol|lldp|portName", "Port 1"},
				new String[]{"cpu|demandmhz", "100"},
				new String[]{null, "x"});
		List<String> debug = new ArrayList<>();
		List<String[]> got = VmnicLldp.neighbours(props, "esx01", debug::add);
		T.eq(1, got.size(), "only the complete LLDP pair");
		T.eq("vmnic0", got.get(0)[0], "vmnic");
		T.eq("usw-xg-8", got.get(0)[1], "system name");
		T.eq("Port 7", got.get(0)[2], "port name");
		T.eq(1, debug.size(), "vmnic1 has no port name: reported at debug");
		T.check(debug.get(0).contains("esx01") && debug.get(0).contains("vmnic1"),
				debug.get(0));
	}
}
