package com.radminvpn.android.signaling

import com.google.firebase.database.*
import com.radminvpn.android.model.PeerInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Firebase Realtime Database для signaling.
 * Структура:
 * networks/
 *   {networkId}/
 *     creatorId: String
 *     createdAt: Long
 *     peers/
 *       {peerId}/
 *         virtualIp: String
 *         offer: String (SDP)
 *         answer: String (SDP)
 *         iceCandidates/
 *           {index}: String
 */
class FirebaseSignaling {

    private val database = FirebaseDatabase.getInstance()
    private val networksRef = database.getReference("networks")

    val localPeerId: String = UUID.randomUUID().toString().take(8)

    /**
     * Создать новую сеть. Возвращает ID сети (6 символов)
     */
    suspend fun createNetwork(): String {
        val networkId = generateNetworkId()
        val networkData = mapOf(
            "creatorId" to localPeerId,
            "createdAt" to ServerValue.TIMESTAMP
        )
        networksRef.child(networkId).setValue(networkData).await()

        // Добавить себя как первого пира
        val peerData = mapOf(
            "peerId" to localPeerId,
            "virtualIp" to "10.0.0.1",
            "joinedAt" to ServerValue.TIMESTAMP
        )
        networksRef.child(networkId).child("peers").child(localPeerId)
            .setValue(peerData).await()

        return networkId
    }

    /**
     * Присоединиться к существующей сети
     */
    suspend fun joinNetwork(networkId: String): String {
        val snapshot = networksRef.child(networkId).get().await()
        if (!snapshot.exists()) {
            throw IllegalArgumentException("Сеть $networkId не найдена")
        }

        // Определить IP для нового пира
        val peersSnapshot = snapshot.child("peers")
        val nextIp = "10.0.0.${peersSnapshot.childrenCount + 1}"

        val peerData = mapOf(
            "peerId" to localPeerId,
            "virtualIp" to nextIp,
            "joinedAt" to ServerValue.TIMESTAMP
        )
        networksRef.child(networkId).child("peers").child(localPeerId)
            .setValue(peerData).await()

        return nextIp
    }

    /**
     * Отправить SDP offer для конкретного пира
     */
    suspend fun sendOffer(networkId: String, targetPeerId: String, sdpOffer: String) {
        networksRef.child(networkId)
            .child("signaling")
            .child("${localPeerId}_to_$targetPeerId")
            .child("offer")
            .setValue(sdpOffer).await()
    }

    /**
     * Отправить SDP answer
     */
    suspend fun sendAnswer(networkId: String, targetPeerId: String, sdpAnswer: String) {
        networksRef.child(networkId)
            .child("signaling")
            .child("${targetPeerId}_to_$localPeerId")
            .child("answer")
            .setValue(sdpAnswer).await()
    }

    /**
     * Отправить ICE candidate
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
     * Слушать входящие offers (для тех кто уже в сети)
     */
    fun listenForOffers(networkId: String): Flow<Pair<String, String>> = callbackFlow {
        val signalingRef = networksRef.child(networkId).child("signaling")

        val listener = signalingRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val key = snapshot.key ?: return
                // Формат ключа: {fromPeerId}_to_{toPeerId}
                if (key.endsWith("_to_$localPeerId")) {
                    val fromPeerId = key.removeSuffix("_to_$localPeerId")
                    val offer = snapshot.child("offer").getValue(String::class.java)
                    if (offer != null) {
                        trySend(Pair(fromPeerId, offer))
                    }
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val key = snapshot.key ?: return
                if (key.endsWith("_to_$localPeerId")) {
                    val fromPeerId = key.removeSuffix("_to_$localPeerId")
                    val offer = snapshot.child("offer").getValue(String::class.java)
                    if (offer != null) {
                        trySend(Pair(fromPeerId, offer))
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        })

        awaitClose { signalingRef.removeEventListener(listener) }
    }

    /**
     * Слушать ответы на мои offer'ы
     */
    fun listenForAnswers(networkId: String): Flow<Pair<String, String>> = callbackFlow {
        val signalingRef = networksRef.child(networkId).child("signaling")

        val listener = signalingRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                checkForAnswer(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                checkForAnswer(snapshot)
            }

            private fun checkForAnswer(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                if (key.startsWith("${localPeerId}_to_")) {
                    val answer = snapshot.child("answer").getValue(String::class.java)
                    val targetPeerId = key.removePrefix("${localPeerId}_to_")
                    if (answer != null) {
                        trySend(Pair(targetPeerId, answer))
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        })

        awaitClose { signalingRef.removeEventListener(listener) }
    }

    /**
     * Слушать ICE candidates от конкретного пира
     */
    fun listenForIceCandidates(networkId: String, fromPeerId: String): Flow<String> = callbackFlow {
        val candidatesRef = networksRef.child(networkId)
            .child("signaling")
            .child("${fromPeerId}_to_$localPeerId")
            .child("iceCandidates")

        val listener = candidatesRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidate = snapshot.getValue(String::class.java)
                if (candidate != null) {
                    trySend(candidate)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        })

        awaitClose { candidatesRef.removeEventListener(listener) }
    }

    /**
     * Слушать новых пиров в сети
     */
    fun listenForPeers(networkId: String): Flow<PeerInfo> = callbackFlow {
        val peersRef = networksRef.child(networkId).child("peers")

        val listener = peersRef.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val peerId = snapshot.child("peerId").getValue(String::class.java) ?: return
                if (peerId != localPeerId) {
                    val virtualIp = snapshot.child("virtualIp").getValue(String::class.java) ?: ""
                    trySend(PeerInfo(peerId = peerId, virtualIp = virtualIp))
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        })

        awaitClose { peersRef.removeEventListener(listener) }
    }

    /**
     * Покинуть сеть
     */
    suspend fun leaveNetwork(networkId: String) {
        networksRef.child(networkId).child("peers").child(localPeerId).removeValue().await()
        networksRef.child(networkId).child("signaling").get().await().children.forEach { child ->
            val key = child.key ?: return@forEach
            if (key.startsWith(localPeerId) || key.endsWith(localPeerId)) {
                child.ref.removeValue().await()
            }
        }
    }

    private fun generateNetworkId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
