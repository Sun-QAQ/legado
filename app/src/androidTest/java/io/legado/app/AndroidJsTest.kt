package io.legado.app

import com.script.ScriptException
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import org.intellij.lang.annotations.Language
import org.junit.Assert
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.net.URLEncoder

class AndroidJsTest {

    @Test
    fun testPackages() {
        @Language("js")
        val js = """
            var accessKeyId = '1111';
            var accessKeySecret = '2222';
            var timestamp = '3333';
            var Mac = Packages.javax.crypto.Mac;
            var SecretKeySpec = Packages.javax.crypto.spec.SecretKeySpec;
            var URLEncoder = Packages.java.net.URLEncoder;
            var JavaString = Packages.java.lang.String;
            var Base64 = Packages.android.util.Base64;
                function percentEncode(value) {
                    if (value == null) throw new Error('percentEncode received ' + value);
                    return String(URLEncoder.encode(value, "UTF-8")).replace(/\+/g, "%20")
                        .replace(/\*/g, "%2A").replace(/%7E/g, "~")
                }
            
                function sign(stringToSign, accessKeySecret) {
                    var mac = Mac.getInstance('HmacSHA1');
                    mac.init(new SecretKeySpec(new JavaString(accessKeySecret + '&').getBytes("UTF-8"), "HmacSHA1"));
                    var signData = mac.doFinal(new JavaString(stringToSign).getBytes("UTF-8"));
                    var signBase64 = Base64.encodeToString(signData, Base64.NO_WRAP);
                    var signUrlEncode = percentEncode(signBase64);
                    return signUrlEncode;
                }
            var query = 'AccessKeyId=' + accessKeyId + '&Action=CreateToken&Format=JSON&RegionId=cn-shanghai&SignatureMethod=HMAC-SHA1&SignatureNonce=' + "xxccrr" + '&SignatureVersion=1.0&Timestamp=' + percentEncode(timestamp) + '&Version=2019-02-28';
            var signStr = sign('GET&' + percentEncode('/') + '&' + percentEncode(query), accessKeySecret);
            var queryStringWithSign = "Signature=" + signStr + "&" + query;
            queryStringWithSign
        """.trimIndent()
        fun percentEncode(value: String) = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
        val query = "AccessKeyId=1111&Action=CreateToken&Format=JSON&RegionId=cn-shanghai" +
            "&SignatureMethod=HMAC-SHA1&SignatureNonce=xxccrr&SignatureVersion=1.0" +
            "&Timestamp=3333&Version=2019-02-28"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec("2222&".toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = Base64.encodeToString(
            mac.doFinal(("GET&" + percentEncode("/") + "&" + percentEncode(query)).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        Assert.assertEquals("Signature=" + percentEncode(signature) + "&" + query, RhinoScriptEngine.eval(js))
        @Language("js")
        val js1 = """
            var returnData = new Packages.io.legado.app.api.ReturnData()
            returnData.getErrorMsg()
        """.trimIndent()
        val result1 = RhinoScriptEngine.eval(js1)
        Assert.assertEquals(result1, "未知错误,请联系开发者!")
    }

    @Test
    fun testJavaImporterEncoding() {
        val result = RhinoScriptEngine.eval("""
            var importer = new JavaImporter(Packages.java.net.URLEncoder);
            var encoded;
            with (importer) {
                encoded = URLEncoder.encode('a b*~', 'UTF-8');
            }
            String(encoded);
        """.trimIndent())
        Assert.assertEquals("a+b*%7E", result)
    }

    @Test
    fun testPackages1() {
        @Language("js")
        val js = """
            var ji = new JavaImporter(Packages.org.mozilla.javascript.DefiningClassLoader)
            with(ji) {
              let x = DefiningClassLoader.getSystemClassLoader()
            }
        """.trimIndent()
        val error = Assert.assertThrows(ScriptException::class.java) { RhinoScriptEngine.eval(js) }
        Assert.assertTrue(error.message.orEmpty().contains("DefiningClassLoader"))
    }

    @Test
    fun testMap() {
        val map = hashMapOf("id" to "3242532321")
        val bindings = ScriptBindings()
        bindings["result"] = map
        @Language("js")
        val jsMap = "$=result;id=$.id;id"
        val result = RhinoScriptEngine.eval(jsMap, bindings)
        Assert.assertEquals("3242532321", result)
        @Language("js")
        val jsMap1 = """result.get("id")"""
        val result1 = RhinoScriptEngine.eval(jsMap1, bindings)
        Assert.assertEquals("3242532321", result1)
    }

}
