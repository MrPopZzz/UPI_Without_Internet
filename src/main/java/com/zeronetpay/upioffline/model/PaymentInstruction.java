package com.zeronetpay.upioffline.model;

import java.math.BigDecimal;

/**
 * The actual payment instruction. After the server decrypts the MeshPacket.ciphertext,
 * it gets one of these.
 * 
 *  Critical fields for security:
 *      - nonce: a UUID unique to this payment. This will make sure the ciphertext and hash
 *               are different for every payment.
 *      - signedAt: lets the server reject stale packets. WEithout these, an attacker
 *                  who got the ciphertext could replay it weeks later.
 *      - pinHash: a hash, held by bank, is required to verify the UPI PIN entered
 *                 by a user. Here we just record it for realism.
 */

public class PaymentInstruction {

	private String senderVpa;
	private String receiverVpa;
	private BigDecimal amount;
	private String pinHash;
	private String nonce; // UUID, unique per payment intent
	private String signedAt; // epoch millis, when sender signed
	
	public PaymentInstruction() {}
	
	public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
			String pinHash, String nonce, String signedAt) {
		this.senderVpa = senderVpa;
		this.receiverVpa = receiverVpa;
		this.amount = amount;
		this.pinHash = pinHash;
		this.nonce = nonce;
		this.signedAt = signedAt;
	}
	
	public String getSenderVpa() { return senderVpa; }
	public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }
	
	public String getReceiverVpa() { return receiverVpa; }
	public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }
	
	public BigDecimal getAmount() { return amount; }
	public void setAmount(BigDecimal amount) { this.amount = amount; }
	
	public String getPinHash() { return pinHash; }
	public void setPinHash(String pinHash) { this.pinHash = pinHash; }
	
	public String getNonce() { return nonce; }
	public void setNonce(String nonce) { this.nonce = nonce; }
	
	public String getSignedAt() { return signedAt; }
	public void setSignedAt(String signedAt) { this.signedAt = signedAt; }
}
