// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc
import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import org.aaustralian.dieselbridge.api.*

class DieselPublicApiService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); private val lock=Any(); private val sessions=linkedMapOf<Long,RemoteSession>(); private var nextId=1L; private lateinit var policy:DieselPublicAccessPolicy; private var observed:DieselPublicRuntimeContext?=null
    override fun onCreate(){super.onCreate();policy=DieselPublicAccessPolicy(applicationContext);observed=DieselPublicRuntimeAccess.runtime.value;scope.launch{DieselPublicRuntimeAccess.runtime.drop(1).collect{cur->val prev=observed;observed=cur;if(prev!=null&&cur!==prev)closeAll("runtime_detached")}}}
    override fun onBind(intent:Intent?):IBinder=root
    override fun onDestroy(){closeAll("service_destroyed");scope.cancel();super.onDestroy()}
    private val root=object:IDieselPublicService.Stub(){
        override fun getServiceInfo():Bundle=DieselIpcCodec.encodeServiceInfo(DieselPublicRuntimeAccess.runtime.value?.isActive==true)
        override fun openSession(clientMinMajor:Int,clientMaxMajor:Int,clientToken:IDieselClientToken?):IDieselSession{
            val v=DieselApiVersion.CURRENT;if(clientMinMajor>v.major||clientMaxMajor<v.major)throw ServiceSpecificException(2,"No compatible Diesel API major version")
            val token=clientToken?:throw ServiceSpecificException(4,"Client death token required")
            val runtime=DieselPublicRuntimeAccess.runtime.value?.takeIf{it.isActive}?:throw ServiceSpecificException(1,"Diesel runtime is not active")
            val uid=Binder.getCallingUid(); val ident=DieselCallerIdentity(uid,packageManager.getPackagesForUid(uid)?.toList()?.sorted().orEmpty())
            val id=synchronized(lock){if(sessions.size>=8)throw ServiceSpecificException(3,"Session limit reached");nextId++}
            lateinit var s:RemoteSession
            s=RemoteSession(id,ident,runtime,policy,token.asBinder()){closedId->synchronized(lock){if(sessions[closedId]===s)sessions.remove(closedId)}}
            try{s.link()}catch(e:RemoteException){s.closeInternal("client_already_dead");throw ServiceSpecificException(4,"Client already dead")}
            synchronized(lock){if(DieselPublicRuntimeAccess.runtime.value!==runtime||!runtime.isActive){s.closeInternal("runtime_changed");throw ServiceSpecificException(1,"Runtime changed")};sessions[id]=s}
            return s
        }
    }
    private fun closeAll(reason:String){val all=synchronized(lock){sessions.values.toList().also{sessions.clear()}};all.forEach{it.closeInternal(reason)}}
    private class RemoteSession(val id:Long,val identity:DieselCallerIdentity,val runtime:DieselPublicRuntimeContext,val policy:DieselPublicAccessPolicy,val token:IBinder,val onClosed:(Long)->Unit):IDieselSession.Stub(),IBinder.DeathRecipient{
        private val closed=AtomicBoolean(false); private val job=SupervisorJob(runtime.scope.coroutineContext[Job]); private val scope=CoroutineScope(runtime.scope.coroutineContext+job)
        fun link(){token.linkToDeath(this,0)}; override fun binderDied(){closeInternal("client_process_died")}
        override fun getCatalog():Bundle{checkCaller();val snap=runtime.api.catalog();val visible=snap.resources.filter{d->d.operations.any{o->policy.canAccess(identity,d,o)}};return DieselIpcCodec.encodeCatalog(snap,visible)}
        override fun getState(resourceId:String):Bundle{checkCaller();val d=runtime.api.descriptor(resourceId);if(d==null||d.kind!=DieselResourceKind.STATE||!policy.canAccess(identity,d,DieselResourceOperation.READ))return DieselIpcCodec.encodeStateResult(DieselPublicStateResult.NotFound(resourceId));return DieselIpcCodec.encodeStateResult(runtime.api.getState(resourceId))}
        override fun invokeAction(resourceId:String,input:Bundle,callback:IDieselActionCallback?){checkCaller();val cb=callback?:return;val d=runtime.api.descriptor(resourceId);if(d==null||d.kind!=DieselResourceKind.ACTION||!policy.canAccess(identity,d,DieselResourceOperation.INVOKE)){deliver(cb,DieselPublicActionResult.NotFound(resourceId));return};when(val dec=DieselIpcCodec.decodeValue(input)){is DieselPublicDecodeResult.Invalid->deliver(cb,DieselPublicActionResult.InvalidInput(dec.reason));is DieselPublicDecodeResult.Value->scope.launch{deliver(cb,runtime.api.invokeAction(resourceId,dec.value))}}}
        override fun close(){checkCaller();closeInternal("client_closed")}
        private fun deliver(cb:IDieselActionCallback,r:DieselPublicActionResult){try{cb.onResult(DieselIpcCodec.encodeActionResult(r))}catch(_:RemoteException){}}
        private fun checkCaller(){check(!closed.get()){ "Diesel public session is closed" };if(Binder.getCallingUid()!=identity.uid)throw SecurityException("Session belongs to another UID");if(!runtime.isActive){closeInternal("runtime_inactive");throw IllegalStateException("Runtime inactive")}}
        fun closeInternal(reason:String){@Suppress("UNUSED_VARIABLE")val why=reason;if(!closed.compareAndSet(false,true))return;runCatching{token.unlinkToDeath(this,0)};job.cancel();onClosed(id)}
    }
}
