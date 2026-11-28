package com.getrich.grok

import org.json.JSONObject

object WebActionInjector {
    private fun coords(action: VisionAction) =
        "const px=Math.round(" + action.x + "*window.innerWidth/1280);const py=Math.round(" + action.y + "*window.innerHeight/800);"

    fun inspectClick(action: VisionAction) = """
(function(){
\${coords(action)}
const el=document.elementFromPoint(px,py);
if(!el) return JSON.stringify({ok:false,blocked:true,reason:'No element at the AI coordinates.'});
let parts=[], n=el;
for(let i=0;i<4&&n;i++,n=n.parentElement){parts.push(n.innerText||'',n.getAttribute?.('aria-label')||'',n.getAttribute?.('title')||'');}
const label=parts.join(' ').replace(/\s+/g,' ').trim();
const danger=/\b(buy|sell|order|confirm|submit|place trade|place order|execute trade|call|put|higher|lower)\b/i.test(label);
return JSON.stringify({ok:true,blocked:danger,tag:el.tagName,label:label.slice(0,300),reason:danger?'Order-related control detected.':''});
})()
""".trimIndent()

    fun click(action: VisionAction) = """
(function(){
\${coords(action)}
const el=document.elementFromPoint(px,py);
if(!el) return JSON.stringify({ok:false,reason:'Target disappeared; click cancelled.'});
let n=el, parts=[];
for(let i=0;i<4&&n;i++,n=n.parentElement){parts.push(n.innerText||'',n.getAttribute?.('aria-label')||'',n.getAttribute?.('title')||'');}
const label=parts.join(' ').replace(/\s+/g,' ').trim();
if(/\b(buy|sell|order|confirm|submit|place trade|place order|execute trade|call|put|higher|lower)\b/i.test(label))
 return JSON.stringify({ok:false,blocked:true,reason:'Order control re-check blocked the click.'});
el.scrollIntoView({block:'center',inline:'center'});el.click();
return JSON.stringify({ok:true,reason:'Click dispatched.'});
})()
""".trimIndent()

    fun approvedClick(action: VisionAction) = """
(function(){
\${coords(action)}
const el=document.elementFromPoint(px,py);
if(!el) return JSON.stringify({ok:false,reason:'Target disappeared; no click was made.'});
el.click();
return JSON.stringify({ok:true,reason:'User-approved click dispatched.'});
})()
""".trimIndent()

    fun inspectType(action: VisionAction) = """
(function(){
${coords(action)}
const el=document.elementFromPoint(px,py);
if(!el) return JSON.stringify({ok:false,blocked:true,reason:'No field at the AI coordinates.'});
const type=(el.getAttribute('type')||'').toLowerCase();
const hint=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.name||'')+' '+(el.parentElement?.innerText||'')).toLowerCase();
if(type==='password'||type==='hidden'||/password|passcode|otp|2fa|verification|security code|credit card|cvv/.test(hint))
 return JSON.stringify({ok:false,blocked:true,sensitive:true,reason:'Sensitive input is reserved for manual entry.'});
if(/\\b(buy|sell|order|confirm|submit|place trade|place order|execute trade)\\b/i.test(hint))
 return JSON.stringify({ok:false,blocked:true,orderRelated:true,reason:'Order-related input detected.'});
if(!(['INPUT','TEXTAREA'].includes(el.tagName)||el.isContentEditable))
 return JSON.stringify({ok:false,blocked:true,reason:'Target is not an editable text field.'});
return JSON.stringify({ok:true,blocked:false});
})()
""".trimIndent()

    fun type(action: VisionAction): String {
        val text = JSONObject.quote(action.text)
        return """
(function(){
\${coords(action)}
const el=document.elementFromPoint(px,py);
if(!el) return JSON.stringify({ok:false,reason:'No input at the AI coordinates.'});
const type=(el.getAttribute('type')||'').toLowerCase();
const hint=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.name||'')).toLowerCase();
if(type==='password'||type==='hidden'||/password|passcode|otp|2fa|verification|security code|credit card|cvv/.test(hint))
 return JSON.stringify({ok:false,blocked:true,reason:'Sensitive input is reserved for manual entry.'});
if(!(['INPUT','TEXTAREA'].includes(el.tagName)||el.isContentEditable))
 return JSON.stringify({ok:false,reason:'Target is not an editable text field.'});
el.focus();const value=$text;
if(el.isContentEditable){el.textContent=value;}
else{const setter=Object.getOwnPropertyDescriptor(el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value')?.set;if(setter)setter.call(el,value);else el.value=value;}
el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
return JSON.stringify({ok:true,reason:'Text entered. Review it in the page.'});
})()
""".trimIndent()
    }
}
