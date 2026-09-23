package io.legado.app.model.webBook

import com.script.ScriptBindings
import io.legado.app.help.source.NgJsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NgJsRuntimeTest {
    private fun crypto() = File("src/main/assets/js/crypto-js.js").readText()

    @Test fun cryptoAndCallsUseIsolatedScopes() = runBlocking {
        val script = "var counter=0; function search(key,page){ counter++; return [key,page,counter,CryptoJS.MD5('abc').toString()]; }"
        val expected = "[\"hello\",2,1,\"900150983cd24fb0d6963f7d28e17f72\"]"
        repeat(2) {
            assertEquals(expected, NgJsRuntime.evaluateScript(script,
                "JSON.stringify(search('hello',2))", ScriptBindings(), crypto()))
        }
    }

    @Test fun validatesWrongFunctionResults() {
        assertThrows(IllegalArgumentException::class.java) { NgJsBook.requireList("{}", "search", "name") }
        assertThrows(IllegalArgumentException::class.java) { NgJsBook.requireList("[null]", "search", "name") }
        assertThrows(IllegalArgumentException::class.java) { NgJsBook.requireObject("[]", "getBookInfo") }
        NgJsBook.requireList("[]", "search", "name")
    }

    /** 本地验收附件；CI 没有附件时跳过，不把第三方书源和密钥提交到仓库。 */
    @Test fun suppliedQimaoScriptRunsWithOfflineResponses() = runBlocking {
        val path = System.getenv("NG_JS_SOURCE_FIXTURE")
        assumeTrue(path != null && File(path).isFile)
        val script = File(path!!).readText()
        assertEquals("七猫直连 · NG JS", NgJsSource.parse(script).bookSourceName)
        val prelude = """
            var saved = '', vars = {};
            var source = {getLoginInfo:function(){return saved;},putLoginInfo:function(v){saved=v;return true;},
                get:function(k){return vars[k]||'';},put:function(k,v){vars[k]=v;}};
            var java = {ajax:function(url){
                if(url.indexOf('/login/tourist')>=0) return JSON.stringify({data:{token:'test',reg:'test',id:'1'}});
                if(url.indexOf('/search/')>=0) return JSON.stringify({data:{books:[{data_type:'book',id:'123',title:'测试书',author:'作者'}]}});
                if(url.indexOf('/reader/detail')>=0) return JSON.stringify({data:{title:'测试书',author:'作者'}});
                if(url.indexOf('/chapter-list')>=0) return JSON.stringify({data:{chapter_lists:[{id:'456',title:'第一章'}]}});
                if(url.indexOf('/chapter/content')>=0) {
                    var iv = CryptoJS.enc.Hex.parse('000102030405060708090a0b0c0d0e0f');
                    var ciphertext = CryptoJS.AES.encrypt('测试正文',CryptoJS.enc.Hex.parse(QM_AES_KEY_HEX),{iv:iv}).ciphertext;
                    return JSON.stringify({data:{content:iv.clone().concat(ciphertext).toString(CryptoJS.enc.Base64)}});
                }
                throw new Error('意外请求: '+url);
            },toast:function(){},log:function(){}};
        """.trimIndent()
        val result = NgJsRuntime.evaluateScript(script, """
            (function(){
                var b=search('测试',1)[0], info=getBookInfo(b), chapters=getChapters(info);
                var content=getContent(chapters[0],info,null);
                loginAction('disableReview',{},null);
                return JSON.stringify([b.name,info.tocUrl,chapters[0].title,content,loginUi().rows.length,source.get('qm_paracomment')]);
            })()
        """.trimIndent(), ScriptBindings(), crypto() + "\n" + prelude)
        assertEquals("[\"测试书\",\"https://www.qimao.com/shuku/123/\",\"第一章\",\"测试正文\",3,\"off\"]", result)
    }
}
