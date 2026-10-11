// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc
import android.os.Bundle
import org.aaustralian.dieselbridge.api.*
import org.aaustralian.dieselbridge.protocol.DieselProtocolRules
import org.aaustralian.dieselbridge.protocol.DieselValue

internal object DieselIpcCodec {
    const val STATUS_OK="ok"; const val STATUS_NOT_FOUND="not_found"; const val STATUS_INVALID="invalid"; const val STATUS_UNAVAILABLE="unavailable"; const val STATUS_REJECTED="rejected"; const val STATUS_FAILED="failed"
    fun encodeServiceInfo(runtimeAvailable:Boolean)=Bundle().apply { putInt("apiMajor",DieselApiVersion.CURRENT.major); putInt("apiMinor",DieselApiVersion.CURRENT.minor); putBoolean("runtimeAvailable",runtimeAvailable) }
    fun encodeCatalog(snapshot:DieselCatalogSnapshot, resources:List<DieselResourceDescriptor> = snapshot.resources)=Bundle().apply {
        putInt("apiMajor",snapshot.apiVersion.major); putInt("apiMinor",snapshot.apiVersion.minor)
        putParcelableArrayList("resources",ArrayList(resources.map { d -> Bundle().apply { putString("id",d.id); putString("kind",d.kind.name.lowercase()); putString("schema",d.schema); putStringArrayList("operations",ArrayList(d.operations.map{it.name.lowercase()}.sorted())); putString("summary",d.summary) } }))
    }
    fun encodeStateResult(r:DieselPublicStateResult)=when(r){
        is DieselPublicStateResult.Success -> Bundle().apply { putString("status",STATUS_OK); putBoolean("present",r.snapshot.value!=null); r.snapshot.value?.let { putBundle("value",encodeValue(it)); putLong("revision",requireNotNull(r.snapshot.revision)); putLong("updatedAtMs",requireNotNull(r.snapshot.updatedAtMs)) } }
        is DieselPublicStateResult.NotFound -> encodeNotFound(r.resourceId)
        is DieselPublicStateResult.Failed -> Bundle().apply { putString("status",STATUS_FAILED); putString("reason",r.reason) }
    }
    fun encodeActionResult(r:DieselPublicActionResult)=when(r){
        is DieselPublicActionResult.Success -> Bundle().apply { putString("status",STATUS_OK); putBundle("value",encodeValue(r.value)) }
        is DieselPublicActionResult.NotFound -> encodeNotFound(r.resourceId)
        is DieselPublicActionResult.InvalidInput -> Bundle().apply { putString("status",STATUS_INVALID); putString("reason",r.reason) }
        DieselPublicActionResult.Unavailable -> Bundle().apply { putString("status",STATUS_UNAVAILABLE) }
        is DieselPublicActionResult.Rejected -> Bundle().apply { putString("status",STATUS_REJECTED); putString("reason",r.reason) }
        is DieselPublicActionResult.Failed -> Bundle().apply { putString("status",STATUS_FAILED); putString("reason",r.reason) }
    }
    fun encodeNotFound(id:String)=Bundle().apply { putString("status",STATUS_NOT_FOUND); putString("resourceId",id) }
    fun encodeValue(v:DieselValue):Bundle=when(v){
        DieselValue.Null -> Bundle().apply{putString("type","null")}
        is DieselValue.Text -> Bundle().apply{putString("type","text");putString("text",v.value)}
        is DieselValue.Integer -> Bundle().apply{putString("type","integer");putLong("integer",v.value)}
        is DieselValue.Decimal -> Bundle().apply{putString("type","decimal");putDouble("decimal",v.value)}
        is DieselValue.Flag -> Bundle().apply{putString("type","flag");putBoolean("flag",v.value)}
        is DieselValue.ObjectValue -> Bundle().apply{putString("type","object");putBundle("object",Bundle().apply{v.value.forEach{(k,n)->putBundle(k,encodeValue(n))}})}
        is DieselValue.ListValue -> Bundle().apply{putString("type","list");putParcelableArrayList("list",ArrayList(v.value.map(::encodeValue)))}
    }
    fun decodeValue(bundle:Bundle):DieselPublicDecodeResult<DieselValue> = try { DieselPublicDecodeResult.Value(dec(bundle,1,Budget())) } catch(e:Bad){ DieselPublicDecodeResult.Invalid(e.reason) }
    private fun dec(b:Bundle,depth:Int,budget:Budget):DieselValue {
        if(depth>DieselProtocolRules.MAX_VALUE_DEPTH) bad("structure_too_deep"); budget.use()
        return when(b.getString("type")){
            "null"->DieselValue.Null
            "text"->{ val s=b.getString("text")?:bad("missing_text"); if(s.toByteArray().size>DieselProtocolRules.MAX_TEXT_VALUE_BYTES) bad("text_too_large"); DieselValue.Text(s) }
            "integer"->{ if(!b.containsKey("integer")) bad("missing_integer"); DieselValue.Integer(b.getLong("integer")) }
            "decimal"->{ if(!b.containsKey("decimal")) bad("missing_decimal"); val d=b.getDouble("decimal"); if(!d.isFinite()) bad("invalid_decimal"); DieselValue.Decimal(d) }
            "flag"->{ if(!b.containsKey("flag")) bad("missing_flag"); DieselValue.Flag(b.getBoolean("flag")) }
            "object"->{ val n=b.getBundle("object")?:bad("missing_object"); if(n.size()>DieselProtocolRules.MAX_COLLECTION_ENTRIES) bad("object_too_large"); val m=linkedMapOf<String,DieselValue>(); n.keySet().sorted().forEach{k-> if(!DieselProtocolRules.isValidFieldName(k)) bad("invalid_field_name"); m[k]=dec(n.getBundle(k)?:bad("invalid_object_value"),depth+1,budget)}; DieselValue.ObjectValue(m) }
            "list"->{ @Suppress("DEPRECATION") val xs=b.getParcelableArrayList<Bundle>("list")?:bad("missing_list"); if(xs.size>DieselProtocolRules.MAX_COLLECTION_ENTRIES) bad("list_too_large"); DieselValue.ListValue(xs.map{dec(it,depth+1,budget)}) }
            else->bad("invalid_value_type")
        }
    }
    private class Budget{var n=0;fun use(){n++;if(n>DieselProtocolRules.MAX_VALUE_NODES)bad("too_many_value_nodes")}}
    private class Bad(val reason:String):RuntimeException(); private fun bad(r:String):Nothing=throw Bad(r)
}
