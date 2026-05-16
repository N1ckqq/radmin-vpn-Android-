package com.radminvpn.android.signaling

import com.google.firebase.database.*
import com.radminvpn.android.model.PeerInfo
import com.radminvpn.android.util.VpnLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Firebase Realtime Database signaling.
 * Handles peer discovery, SDP exchange, and ICE candidate relay.
 *
 * Database structure:
 * networks/{networkId}/
 *   creatorId: String
 *   createdAt: Long
 *   peers/{peerId}/
 *     peerId: String
 *     virtualIp: String
 *     joinedAt: Long
 *   signaling/{fromPeerId}_to_{toPeerId}/
 *     offer: String (SDP)
 *     answer: String (SDP)
 *     iceCandidates/{pushId}: String
 */
class FirebaseSignaling {

    companion object {
        private const val TAG = "Signaling"
    }

    private val database = FirebaseDatabase.getInstance()
    private val networksRef = database.getReference("networks")

    val localPeerId: String = UUID.randomUUID().toString().take(8)

    init {
        VpnLog.i(TAG, "Local peer ID: $localPeerId")
    }

    /**
     * Create a new network. Returns the 6-character network ID.
     */
    suspend fun createNetwork(): String {
        val networkId = generateNetworkId()
        VpnLog.d(TAG, "Creating network: $networkId")

        val networkData = mapOf(
            "creatorId" to localPeerId,
            "createdAt" to ServerValue.TIMESTAMP
        )
        networksRef.child(networkId).setValue(networkData).await()

        // Add self as first peer
        val peerData = mapOf(
            "peerId" to localPeerId,
            "virtualIp" to "10.0.0.1",
            "joinedAt" to ServerValue.TIMESTAMP
        )
        networksRef.child(networkId).child("peers").child(localPeerId)
            .setValue(peerData).await()

        VpnLog.success(TAG, "Network $networkId created in Firebase")
        return networkId
    }

    /**
     * Join an existing network. Returns assigned virtual IP.
     */
    suspend fun joinNetwork(networkId: String): String {
        VpnLog.d(TAG, "Joining network: $networkId")

        val snapshot = networksRef.child(networkId).get().await()
        if (!snapshot.exists()) {
            throw IllegalArgumentException("Network $networkId not found")
        }

        // Determine next IP
        val peersSnapshot = snapshot.child("peers")
        val nextIp = "10.0.0.${peersSnapshot.childrenCount + 1}"

        VpnLog.d(TAG, "Assigned IP: $nextIp (${peersSnapshot.childrenCount} existing peers)")

        val peerData = mapOf(
            "peerId" to localPeerId,
            "virtualIp" to nextIp,
            "joinedAt" to ServerValue.TIMESTAMP
        )
        networksRef.child(networkId).child("peers").child(localPeerId)
            .setValue(peerData).await()

        VpnLog.success(TAG, "Joined network $networkId with IP $nextIp")
        return nextIp
    }

    /**
     * Send SDP offer to a specific peer
     */
    suspend fun sendOffer(networkId: String, targetPeerId: String, sdpOffer: String) {
        VpnLog.d(TAG, "Sending offer to $targetPeerId (${sdpOffer.length} chars)")
        networksRef.child(networkId)
            .child("signaling")
            .child("${localPeerId}_to_$targetPeerId")
            .child("offer")
            .setValue(sdpOffer).await()
    }

    /**
     * Send SDP answer to a specific peer
     */
    suspend fun sendAnswer(networkId: String, targetPeerId: String, sdpAnswer: String) {
        VpnLog.d(TAG, "Sending answer to $targetPeerId (${sdpAnswer.length} chars)")
        networksRef.child(networkId)
            .child("signaling")
            .child("${targetPeerId}_to_$localPeerId")
            .child("answer")
            .setValue(sdpAnswer).await()
    }

    /**
     * Send ICE candidate to a specific peer
     */
    suspend fun sendIceCandidate(networkId: String, targetPeerId: String, candidate: String) {
        networksRef.child(networkId)
            .child("signaling")
            .child("${localPeerId}_to_$targetPeerId")
            .child("iceCandidates")
            .push()
            .setValue(candidate).await()
    }

    /**
     * Listen for incoming SDP offers addressed to us
     */
    fun listenForOffers(networkId: String): Flow<Pair<String, String>> = callbackFlow {
        val signalingRef = networksRef.child(networkId).child("signaling")
        VpnLog.d(TAG, "Listening for offers...")

        val listener = signalingRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processOffer(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                processOffer(snapshot)
            }

            private fun processOffer(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                if (key.endsWith("_to_$localPeerId")) {
                    val fromPeerId = key.removeSuffix("_to_$localPeerId")
                    val offer = snapshot.child("offer").getValue(String::class.java)
                    if (offer != null) {
                        VpnLog.d(TAG, "Offer received from $fromPeerId")
                        trySend(Pair(fromPeerId, offer))
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                VpnLog.e(TAG, "Offer listener cancelled: ${error.message}")
                close(error.toException())
            }
        })

        awaitClose { signalingRef.removeEventListener(listener) }
    }

    /**
     * Listen for SDP answers to our offers
     */
    fun listenForAnswers(networkId: String): Flow<Pair<String, String>> = callbackFlow {
        val signalingRef = networksRef.child(networkId).child("signaling")
        VpnLog.d(TAG, "Listening for answers...")

        val listener = signalingRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processAnswer(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                processAnswer(snapshot)
            }

            private fun processAnswer(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                if (key.startsWith("${localPeerId}_to_")) {
                    val answer = snapshot.child("answer").getValue(String::class.java)
                    val targetPeerId = key.removePrefix("${localPeerId}_to_")
                    if (answer != null) {
                        VpnLog.d(TAG, "Answer received from $targetPeerId")
                        trySend(Pair(targetPeerId, answer))
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                VpnLog.e(TAG, "Answer listener cancelled: ${error.message}")
                close(error.toException())
            }
        })

        awaitClose { signalingRef.removeEventListener(listener) }
    }

    /**
     * Listen for ALL ICE candidates addressed to us from any peer.
     * Returns (fromPeerId, candidateString)
     */
    fun listenForIceCandidates(networkId: String): Flow<Pair<String, String>> = callbackFlow {
        val signalingRef = networksRef.child(networkId).child("signaling")
        VpnLog.d(TAG, "Listening for ICE candidates...")

        val listener = signalingRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processIceCandidates(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                processIceCandidates(snapshot)
            }

            private fun processIceCandidates(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                // Only process signaling entries addressed to us
                if (!key.endsWith("_to_$localPeerId")) return

                val fromPeerId = key.removeSuffix("_to_$localPeerId")
                val candidatesNode = snapshot.child("iceCandidates")
                for (child in candidatesNode.children) {
                    val candidate = child.getValue(String::class.java)
                    if (candidate != null) {
                        trySend(Pair(fromPeerId, candidate))
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                VpnLog.e(TAG, "ICE listener cancelled: ${error.message}")
                close(error.toException())
            }
        })

        awaitClose { signalingRef.removeEventListener(listener) }
    }

    /**
     * Listen for new peers joining the network
     */
    fun listenForPeers(networkId: String): Flow<PeerInfo> = callbackFlow {
        val peersRef = networksRef.child(networkId).child("peers")
        VpnLog.d(TAG, "Listening for peers...")

        val listener = peersRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val peerId = snapshot.child("peerId").getValue(String::class.java) ?: return
                if (peerId != localPeerId) {
                    val virtualIp = snapshot.child("virtualIp").getValue(String::class.java) ?: ""
                    VpnLog.i(TAG, "Peer joined: $peerId ($virtualIp)")
                    trySend(PeerInfo(peerId = peerId, virtualIp = virtualIp))
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val peerId = snapshot.child("peerId").getValue(String::class.java) ?: return
                VpnLog.w(TAG, "Peer left: $peerId")
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                VpnLog.e(TAG, "Peers listener cancelled: ${error.message}")
                close(error.toException())
            }
        })

        awaitClose { peersRef.removeEventListener(listener) }
    }

    /**
     * Leave the network (cleanup)
     */
    suspend fun leaveNetwork(networkId: String) {
        VpnLog.d(TAG, "Leaving network $networkId...")
        try {
            networksRef.child(networkId).child("peers").child(localPeerId).removeValue().await()
            // Clean up signaling data
            val signalingSnapshot = networksRef.child(networkId).child("signaling").get().await()
            signalingSnapshot.children.forEach { child ->
                val key = child.key ?: return@forEach
                if (key.startsWith(localPeerId) || key.endsWith(localPeerId)) {
                    child.ref.removeValue().await()
                }
            }
        } catch (e: Exception) {
            VpnLog.w(TAG, "Error during leave: ${e.message}")
        }
    }

    private fun generateNetworkId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
