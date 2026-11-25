package com.zeronetpay.upioffline.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.zeronetpay.upioffline.model.Account;
import com.zeronetpay.upioffline.model.AccountRepository;
import com.zeronetpay.upioffline.model.MeshPacket;
import com.zeronetpay.upioffline.model.Transaction;
import com.zeronetpay.upioffline.model.TransactionRepository;

/**
 * Public REST Surface
 * 
 * The endpoints split into three groups:
 *   /api/server-key -> simulated senders can fetch the server's public key
 *   /api/mesh/* -> simulator endpoints (inject, gossip, flush)
 *   /api/bridge/ingest -> The real production endpoint. A real bridge node would hit the
 *   /api/accounts, /api/transactions -> for the dashboard
 */

@RestController
@RequestMapping("/api")
public class ApiController {

	@Autowired private ServerKeyHolder serverKey;
	
	@Autowired private ProductionService productionService;
	
	@Autowired private MeshSimulatorService meshSimulatorService;
	
	@Autowired private BridgeIngestionService bridgeIngestionService;
	
	@Autowired private AccountRepository accountRepository;
	
	@Autowired private TransactionRepository transactionRepository;
	
	@Autowired private IndempotencyService idempotencyService;
	
	// key
	@GetMapping("/server-key")
	public Map<String, String> getServerPublicKey() {
		return Map.of(
				"publicKey", serverKey,
				"algorithm", "RSA-2048 / OAEP-SHA256",
				"hybridScheme", "RSA-OAEP encrypts an AES-256-GCM session key"
		);
	}
	
	/**
	 * Simulating a sender phone.....
	 * 
	 * Build a packet on the server and inject it into the mesh
	 * at the given device
	 */
	@PostMapping("/demo/send")
	public ResponseEntity<?> demoSend(@RequestBody DemoSendRequest req) throws Exception {
		MeshPacket packet = productionService.createPacket(
				req.senderVpa, req.receiverVpa, req.amount, req.pin,
				req.ttl == null ? 5 : req.ttl);
		
		String startDevice = req.startDevice == null ? "phone-sayan" : req.startDevice;
		meshSimulatorService.inject(startDevice, packet);
		
		return ResponseEntity.ok(Map.of(
				"packetId", packet.getPacketId(),
				"ciphertextPreview", packet.getCiphertext().substring(0, 64) + "...",
				"ttl", packet.getTtl(),
				"injectedAt", startDevice
		)); 
	}
	
	public static class DemoSendRequest {
		public String senderVpa,;
		public String receiverVpa;
		public BigDecimal amount;
		public String pin;
		public Integer ttl;
		public String startDevice;
	}
	
	/**
	 * Mesh Simulation.........
	 * @return
	 */
	@GetMapping("/mesh/state")
	public Map<String, Object> meshState() {
		List<Map<String, Object>> deviceData = new ArrayList<>();
		
		for(VirtualDevice d : meshSimulatorService.getDevices()) {
			deviceData.add(Map.of(
					"deviceId", d.getDeviceId(),
					"hasInternet", d.hasInternet(),
					"packetCount", d.packetCount(),
					"packetIds", d.getHeldPackets().stream()
										.map(p -> p.getPacketId().substring(0, 8))
										.toList()
			));
		}
		
		return Map.of(
				"devices", deviceData,
				"idempotencyCacheSize", idempotencyService.size()
		);
	}
	
	@PostMapping("/mesh/gossip")
	public Map<String, Object> meshGossip() {
		MeshSimulatorService.GossipResult r = meshSimulatorService.gossipOnce();
		
		return Map.of(
				"transfers", r.transfers(),
				"deviceCounts", r.deviceCounts()
		);
	}
	
	/**
	 * "All bridge nodes simultaneously walk outside and get 4G."
	 * They all upload everything they hold to /api/bridge/ingest.
	 * 
	 * This is the moment the duplicate -storm idempotency case is tested:
	 * if multiple bridge nodes hold the same packet, the server gets multiple
	 * concurrent POSTs of the same ciphertext, and only one should settle.
	 * @return
	 */
	@PostMapping("/mesh/flush")
	public Map<String, Object> meshFlush() {
		List<MeshSimulatorService.BridgeUploads> uploads = meshSimulatorService.collectBridgeUploads();
		List<Map<String, Object>> results = new ArrayList<>();
		
		// upload in parallel to exercise concurrent idempotency
		uploads.parallelStream.forEach(up -> {
				BridgeIngestionService.IngestResult r =
					bridgeIngestionService.ingest(up.packet(), up.bridgeNodeId(), 5 - up.packet().getTtl());
				synchronized (results) {
					results.add(Map.of(
							"bridgeNode", up.getBridgeNodeId(),
							"packetId", up.packet().getPacketId().substring(0, 8),
							"outcome", r.outcome(),
							"reason", r.reason() == null ? "" : r.reason(),
							"transactionId", r.transactionId() == null ? -1 : r.transactionId()		
					));
				}
		});
		
		return Map.of(
				"uploadsAttempted", uploads.size(),
				"results", results
		);
	}
	
	@PostMapping("/mesh/reset")
	public Map<String, Object> meshReset() {
		meshSimulatorService.resetMesh();
		idempotencyService.clear();
		
		return Map.of(
				"status", "mesh idempotency cache cleared"
		);
	}
	
	// .................................... bridge
	
	/**
	 * The Production Endpoint.
	 * 
	 * In the real deployment, the android app's bridge logic POSTs here whenever
	 * the device has internet and is holding mesh packets.  
	 * @param packet
	 * @param bridgeNodeId
	 * @param hopCount
	 * @return
	 */
	@PostMapping("/bridge/ingest")
	public ResponseEntity<?> ingest(
			@RequestBody MeshPacket packet,
			@RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
			@RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {
		
		BridgeIngestionService.IngestResult r = bridgeIngestionService.
				ingest(packet, bridgeNodeId, hopCount);
		
		return ResponseEntity.ok(r);
	}
	
	// ................................ accounts
	
	@GetMapping("/accounts")
	public List<Account> listAccounts() {
		return accountRepository.findAll();
	}
	
	@GetMapping("/transactions")
	public List<Transaction> listTransactions() {
		return transactionRepository.findTop20ByOrderByIdDesc();
	}
}
