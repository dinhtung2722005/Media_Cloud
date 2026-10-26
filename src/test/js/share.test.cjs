const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
async function page(fetch,token='A'.repeat(43)){
    const nodes=new Map();const element=tag=>({tagName:tag.toUpperCase(),children:[],listeners:{},textContent:'',className:'',classList:{add(){},remove(){}},replaceChildren(...children){this.children=children;},appendChild(child){this.children.push(child);},addEventListener(name,action){this.listeners[name]=action;}});
    const document={getElementById(id){if(!nodes.has(id))nodes.set(id,element('div'));return nodes.get(id);},createElement:element};
    const context=vm.createContext({document,location:{pathname:'/s/'+token},fetch,Date,Math,Number,String});
    await vm.runInContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/share.js'),'utf8'),context);return{nodes,document};
}
test('public viewer renders a shared video and its download without login',async()=>{
    const calls=[];const state=await page(async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify({title:'Video.mp4',extension:'mp4',fileSize:1234,expiresAt:'2026-10-27T00:00:00Z'}));});
    const media=state.nodes.get('share-preview').children[0];assert.equal(media.tagName,'VIDEO');assert.equal(media.controls,true);assert.equal(media.autoplay,undefined);assert.match(media.src,/\/stream$/);assert.match(state.nodes.get('share-download').href,/\/download$/);assert.equal(calls[0].options.credentials,'omit');
});
test('invalid or revoked tokens show a safe unavailable state',async()=>{
    const malformed=await page(()=>{throw new Error('Must not fetch');},'../escape');assert.match(malformed.nodes.get('share-title').textContent,/không còn khả dụng/);
    const revoked=await page(async()=>new Response('{}',{status:404}));assert.match(revoked.nodes.get('share-title').textContent,/không còn khả dụng/);assert.equal(revoked.nodes.get('share-download').href,undefined);
});
test('untrusted file names stay text and active documents have no inline preview',async()=>{
    const name='<img src=x onerror=alert(1)>.html';const state=await page(async()=>new Response(JSON.stringify({title:name,extension:'html',fileSize:50,expiresAt:'2026-10-27T00:00:00Z'})));
    assert.equal(state.nodes.get('share-title').textContent,name);assert.equal(state.nodes.get('share-preview').children[0].tagName,'SPAN');assert.ok(state.nodes.get('share-download').href);
});
