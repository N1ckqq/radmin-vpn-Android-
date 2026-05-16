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
 * Simplified Firebase signaling. Key fixes:
 * - Listen on SPECIFIC paths (not parent "signaling" node)
 * - ICE candidates: ChildEventListener on exact ice_from/ path (onChildAdded only)
 * - Offer/answer: ValueEventListener on exact field (fires once)
 * - No onChildChanged replay bugs
 *
 * DB: networks/{netId}/peers/{peerId}, networks/{netId}/signaling/{from}_to_{to}/
 */
class FirebaseSignaling {

    companion object {
        private const val TAG = "Signal"
    }

    private val db = FirebaseDatabase.getInstance()
    private val networksRef = db.getReference("networks")
    private val activeListeners = mutableListOf<Pair<DatabaseReference, Any>>()

    val localPeerId: String = UUID.randomUUID().toString().take(8)

    init {
        VpnLog.i(TAG, "Local peer ID: $localPeerId")
    }

    suspend fun createNetwork(): String {
        val id = generateId()
        networksRef.child(id).child("peers").child(localPeerId)
            .setValue(mapOf("peerId" to localPeerId, "virtualIp" to "10.0.0.1"))
            .await()
        VpnLog.success(TAG, "Created network: $id")
        return id
    }

    suspend fun joinNetwork(networkId: String): String {
        val snap = networksRef.child(networkId).child("peers").get().await()
        if (!snap.exists()) throw Exception("Network not found: $networkId")
        val ip = "10.0.0.${snap.childrenCount + 1}"
        networksRef.child(networkId).child("peers").child(localPeerId)
            .setValue(mapOf("peerId" to localPeerId, "virtualIp" to ip))
            .await()
        VpnLog.success(TAG, "Joined $networkId as $ip")
        return ip
    }

    suspend fun sendOffer(networkId: String, toPeer: String, sdp: String) {
        sigPath(networkId, localPeerId, toPeer).child("offer").setValue(sdp).await()
        VpnLog.d(TAG, "Sent offer to $toPeer")
    }

    suspend fun sendAnswer(networkId: String, fromPeer: String, sdp: String) {
        sigPath(networkId, fromPeer, localPeerId).child("answer").setValue(sdp).await()
        VpnLog.d(TAG, "Sent answer to $fromPeer")
    }

    suspend fun sendIceCandidate(networkId: String, toPeer: String, candidate: String) {
        sigPath(networkId, localPeerId, toPeer).child("ice_from").push().setValue(candidate).await()
    }

    fun listenForPeers(networkId: String): Flow<PeerInfo> = callbackFlow {
        val ref = networksRef.child(networkId).child("peers")
        val listener = ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {
                val id = snap.child("peerId").getValue(String::class.java) ?: return
                if (id == localPeerId) return
                val ip = snap.child("virtualIp").getValue(String::class.java) ?: ""
                VpnLog.i(TAG, "Peer joined: $id ($ip)")
                trySend(PeerInfo(peerId = id, virtualIp = ip))
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) { close(e.toException()) }
        })
        activeListeners.add(ref to listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun listenForOffer(networkId: String, fromPeer: String, callback: (String) -> Unit) {
        val ref = sigPath(networkId, fromPeer, localPeerId).child("offer")
        val listener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val sdp = snap.getValue(String::class.java) ?: return
                VpnLog.i(TAG, "Got offer from $fromPeer")
                callback(sdp)
                ref.removeEventListener(this)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        activeListeners.add(ref to listener)
    }

    fun listenForAnswer(networkId: String, toPeer: String, callback: (String) -> Unit) {
        val ref = sigPath(networkId, localPeerId, toPeer).child("answer")
        val listener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val sdp = snap.getValue(String::class.java) ?: return
                VpnLog.i(TAG, "Got answer from $toPeer")
                callback(sdp)
                ref.removeEventListener(this)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        activeListeners.add(ref to listener)
    }

    fun listenForIceCandidates(networkId: String, fromPeer: String, callback: (String) -> Unit) {
        val ref = sigPath(networkId, fromPeer, localPeerId).child("ice_from")
        val listener = ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, prev: String?) {
                val c = snap.getValue(String::class.java) ?: return
                callback(c)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        })
        activeListeners.add(ref to listener)
    }

    suspend fun leaveNetwork(networkId: String) {
        try { networksRef.child(networkId).child("peers").child(localPeerId).removeValue().await() }
        catch (_: Exception) {}
    }

    fun removeAllListeners() {
        activeListeners.forEach { (ref, l) ->
            when (l) {
                is ValueEventListener -> ref.removeEventListener(l)
                is ChildEventListener -> ref.removeEventListener(l)
            }
        }
        activeListeners.clear()
    }

    private fun sigPath(networkId: String, from: String, to: String): DatabaseReference =
        networksRef.child(networkId).child("signaling").child("${from}_to_$to")

    private fun generateId(): String {
        val c = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { c.random() }.joinToString("")
    }
}
