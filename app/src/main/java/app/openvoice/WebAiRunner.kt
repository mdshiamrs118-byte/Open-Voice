package app.openvoice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives a chat website in an invisible browser: types the prompt, presses send, then watches the page for the
 * JSON answer (found by a one-time id, so it works on any site without message selectors). Main thread only.
 */
class WebAiRunner(private val svc: OpenVoiceService) {

    private val h = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var web: WebView? = null
    private var site: WebSite? = null
    private var pageReady = false
    private var loadCb: ((Boolean) -> Unit)? = null
    private var busy = false

    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()

    fun destroy() {
        h.removeCallbacksAndMessages(null)
        busy = false
        loadCb = null
        web?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
            try {
                it.stopLoading()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        web = null
        site = null
        pageReady = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView? {
        return try {
            val w = WebView(svc)
            val s = w.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.loadWithOverviewMode = true
            s.useWideViewPort = true
            s.mediaPlaybackRequiresUserGesture = true
            s.cacheMode = WebSettings.LOAD_DEFAULT
            s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!pageReady) {
                        pageReady = true
                        h.postDelayed({
                            loadCb?.invoke(true)
                            loadCb = null
                        }, 2500)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        loadCb?.invoke(false)
                        loadCb = null
                    }
                }
            }
            // Parked off-screen: it never covers the real screen or takes touches.
            val lp = WindowManager.LayoutParams(
                dp(380), dp(700), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = -6000
            lp.y = 0
            wm.addView(w, lp)
            w.onResume()
            w.resumeTimers()
            w
        } catch (e: Throwable) {
            null
        }
    }

    private fun ensurePage(s: WebSite, cb: (Boolean) -> Unit) {
        if (web != null && site?.id == s.id && pageReady) {
            cb(true)
            return
        }
        destroy()
        val w = create()
        if (w == null) {
            cb(false)
            return
        }
        web = w
        site = s
        pageReady = false
        loadCb = cb
        w.loadUrl(s.url)
        h.postDelayed({
            if (loadCb != null) {
                loadCb?.invoke(false)
                loadCb = null
            }
        }, 35_000)
    }

    private fun unquote(v: String?): String {
        if (v == null || v == "null") return ""
        return try {
            JSONArray("[$v]").getString(0)
        } catch (e: Exception) {
            v
        }
    }

    private fun js(script: String, cb: (String) -> Unit) {
        val w = web
        if (w == null) {
            cb("")
            return
        }
        w.evaluateJavascript(script) { cb(unquote(it)) }
    }

    private fun arr(list: List<String>) = JSONArray(list).toString()

    private fun typeScript(text: String, s: WebSite) = """
(function(){
  var TEXT = ${JSONObject.quote(text)};
  function vis(e){var r=e.getBoundingClientRect();var st=getComputedStyle(e);return r.width>20&&r.height>10&&st.visibility!=='hidden'&&st.display!=='none';}
  var sels=${arr(s.inputSel)}.concat(['textarea','div[contenteditable="true"]','[contenteditable="plaintext-only"]','div[role="textbox"]','input[type="text"]']);
  var el=null;
  for(var i=0;i<sels.length&&!el;i++){var l=document.querySelectorAll(sels[i]);for(var j=l.length-1;j>=0;j--){if(vis(l[j])){el=l[j];break;}}}
  if(!el)return 'NO_INPUT';
  el.focus();
  if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){
    var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto,'value').set.call(el,TEXT);
    el.dispatchEvent(new Event('input',{bubbles:true}));
  } else {
    document.execCommand('selectAll',false,null);
    var ok=document.execCommand('insertText',false,TEXT);
    if(!ok){el.textContent=TEXT;el.dispatchEvent(new InputEvent('input',{bubbles:true,data:TEXT,inputType:'insertText'}));}
  }
  return 'TYPED';
})()
""".trimIndent()

    private fun sendScript(s: WebSite) = """
(function(){
  function vis(e){var r=e.getBoundingClientRect();var st=getComputedStyle(e);return r.width>8&&r.height>8&&st.visibility!=='hidden'&&st.display!=='none';}
  var sels=${arr(s.sendSel)}.concat(['button[data-testid="send-button"]','button[aria-label*="Send" i]','button[aria-label*="Submit" i]','[role="button"][aria-label*="Send" i]','button[type="submit"]','button[class*="send" i]']);
  for(var i=0;i<sels.length;i++){
    var l=document.querySelectorAll(sels[i]);
    for(var j=l.length-1;j>=0;j--){
      var b=l[j];
      if(vis(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true'){b.click();return 'CLICKED';}
    }
  }
  var el=document.activeElement||document.body;
  function ev(t){return new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true});}
  el.dispatchEvent(ev('keydown'));el.dispatchEvent(ev('keypress'));el.dispatchEvent(ev('keyup'));
  return 'ENTER';
})()
""".trimIndent()

    private fun readScript(nonce: String) = """
(function(){
  var N=${JSONObject.quote(nonce)};
  var t=document.body.innerText;
  var re=new RegExp('\\{\\s*"id"\\s*:\\s*"'+N+'"','g');var m,last=-1;
  while((m=re.exec(t))!==null){last=m.index;}
  if(last<0)return '';
  var depth=0,inStr=false,esc=false;
  for(var i=last;i<t.length;i++){
    var c=t[i];
    if(inStr){if(esc)esc=false;else if(c==='\\')esc=true;else if(c==='"')inStr=false;}
    else{if(c==='"')inStr=true;else if(c==='{')depth++;else if(c==='}'){depth--;if(depth===0)return t.substring(last,i+1);}}
  }
  return '';
})()
""".trimIndent()

    /** [done] gets (json, null) on success or (null, error message). */
    fun ask(s: WebSite, prompt: String, nonce: String, timeoutMs: Long, done: (String?, String?) -> Unit) {
        if (busy) {
            done(null, "the web AI is still busy")
            return
        }
        busy = true
        val finish = { json: String?, err: String? ->
            busy = false
            done(json, err)
        }
        ensurePage(s) { ok ->
            if (!ok) {
                finish(null, "the ${s.name} page didn't load")
            } else {
                typeAndSend(s, prompt, 0) { err ->
                    if (err != null) finish(null, err)
                    else poll(nonce, SystemClock.uptimeMillis() + timeoutMs, finish)
                }
            }
        }
    }

    private fun typeAndSend(s: WebSite, prompt: String, attempt: Int, cb: (String?) -> Unit) {
        js(typeScript(prompt, s)) { r ->
            when {
                r == "TYPED" -> h.postDelayed({
                    js(sendScript(s)) { cb(null) }
                }, 600)
                attempt < 3 -> h.postDelayed({ typeAndSend(s, prompt, attempt + 1, cb) }, 2000)
                else -> cb("couldn't find the chat box on ${s.name}. Open Settings → Web AI and log in")
            }
        }
    }

    private fun poll(nonce: String, deadline: Long, finish: (String?, String?) -> Unit) {
        if (SystemClock.uptimeMillis() > deadline) {
            finish(null, "no reply from the web AI. Are you logged in?")
            return
        }
        js(readScript(nonce)) { r ->
            if (r.isNotEmpty() && try {
                    JSONObject(r)
                    true
                } catch (e: Exception) {
                    false
                }
            ) {
                finish(r, null)
            } else {
                h.postDelayed({ poll(nonce, deadline, finish) }, 900)
            }
        }
    }
}
