/* 账本详情 — 完整版 */
const bookId = window.bookId || '';
let flows = [], members = [];
const cu = JSON.parse(localStorage.getItem('user') || sessionStorage.getItem('user') || '{}');
let cy = new Date().getFullYear(), cm = new Date().getMonth() + 1;
let mt = 'expense', af = 'all';
let tc = null, trc = null;

async function init(){
    const ms=cy+'-'+String(cm).padStart(2,'0');
    const[ir,sr,fr,mr]=await Promise.all([
        api('/api/entry/book/all'),
        api('/api/entry/flow/statistics?bookId='+bookId+'&month='+ms),
        api('/api/entry/flow/all?bookId='+bookId+'&month='+ms),
        api('/api/entry/book/members',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({book_id:bookId})})
    ]);
    if(ir.code===200){const b=ir.data.find(x=>x.book_id===bookId);if(b)document.getElementById('bookName').textContent=b.book_name}
    if(sr.code===200)updateStats(sr.data);else toast('加载统计失败','error')
    if(fr.code===200){flows=fr.data||[];renderFlows()}else toast('加载流水失败','error')
    if(mr.code===200){members=mr.data||[];renderMembers()}else toast('加载成员失败','error')
    document.getElementById('monthLabel').textContent=cy+'年'+cm+'月'
}

function updateStats(s){
    if(!s)return;
    document.getElementById('totalIncome').textContent='¥'+(s.total_income||0).toFixed(0);
    document.getElementById('totalExpense').textContent='¥'+(s.total_expense||0).toFixed(0);
    animateNumber(document.getElementById('balance'), s.balance||0);
    drawCharts(s)
}
function prevMonth(){cm--;if(cm<1){cm=12;cy--}loadMonth()}
function nextMonth(){cm++;if(cm>12){cm=1;cy++}loadMonth()}
async function loadMonth(){
    document.getElementById('monthLabel').textContent=cy+'年'+cm+'月';
    const ms=cy+'-'+String(cm).padStart(2,'0');
    const r=await api('/api/entry/flow/statistics?bookId='+bookId+'&month='+ms);
    if(r.code===200)updateStats(r.data);
    const r2=await api('/api/entry/flow/all?bookId='+bookId+'&month='+ms);
    if(r2.code===200){flows=r2.data||[];renderFlows()}
}

function renderFlows(){
    const l=document.getElementById('flowList');
    const f=af==='all'?flows:flows.filter(x=>x.industry_type===af);
    const s=f.sort((a,b)=>{
        const d=(b.day||'').localeCompare(a.day||'');
        return d!==0?d:(b.id||0)-(a.id||0)
    });
    if(!s.length){l.innerHTML='<div style="text-align:center;padding:30px;color:var(--text-muted);font-size:0.85rem;">暂无记录</div>';return}
    l.innerHTML=s.map((f,i)=>{
        const ic=f.flow_type==='收入';
        const cls=getCls(f.industry_type);const icon=getIcon(f.industry_type);
        return '<div class="tx-item stagger" style="animation-delay:'+((i%20)*0.05)+'s;"><div class="tx-icon '+cls+'"><i class="ph ph-'+icon+'"></i></div><div class="tx-info"><div class="tx-title">'+(f.name||f.industry_type||'未分类')+'</div><div class="tx-meta">'+f.day+' · '+(f.attribution||'我')+(f.user_id===cu.id?' <span class="tx-del" onclick="event.stopPropagation();deleteFlow('+f.id+')" style="color:var(--expense);cursor:pointer;">删除</span>':'')+'</div></div><div class="tx-amount '+(ic?'income':'expense')+'">'+(ic?'+':'-')+'¥'+(f.money||0).toFixed(2)+'</div></div>'
    }).join('')
}
function getCls(c){const m={餐饮:'cat-food',交通:'cat-transport',购物:'cat-shopping',居住:'cat-housing',娱乐:'cat-entertainment',医疗:'cat-medical',教育:'cat-education',其他:'cat-other'};return m[c]||'cat-other'}
function getIcon(c){const m={餐饮:'fork-knife',交通:'bus',购物:'shopping-bag',居住:'house',娱乐:'film-strip',医疗:'heartbeat',教育:'graduation-cap',工资:'wallet',奖金:'gift',投资:'trend-up',其他收入:'plus-circle',其他:'dots-three'};return m[c]||'dots-three'}
function filterFlows(t,el){af=t;document.querySelectorAll('#flowCategoryFilter .category-item').forEach(e=>e.classList.remove('active'));if(el)el.classList.add('active');renderFlows()}

function drawCharts(s){if(typeof Chart==='undefined')return;drawTypeChart(s);drawTrendChart(s)}
function drawTypeChart(s){
    const ctx=document.getElementById('typeChart')?.getContext('2d');if(!ctx)return;
    const ed=(s.type_stats&&s.type_stats['支出'])||{};const cats=Object.keys(ed);
    if(tc)tc.destroy();if(!cats.length){tc=null;return}
    tc=new Chart(ctx,{type:'doughnut',data:{labels:cats,datasets:[{data:cats.map(c=>ed[c]),backgroundColor:['#7C3AED','#A78BFA','#F472B6','#34D399','#F59E0B','#EF4444','#06B6D4','#8B5CF6'],borderWidth:0}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{position:'bottom',labels:{color:'#A99EC4',font:{size:11},padding:12,usePointStyle:true}}}}})
}
function drawTrendChart(s){
    const ctx=document.getElementById('trendChart')?.getContext('2d');if(!ctx)return;
    const daily=s.day_stats||{};const days=Object.keys(daily).sort();
    if(trc)trc.destroy();if(!days.length){trc=null;return}
    const exp=days.map(d=>daily[d]?.expense||0);
    // 画布宽度按天数撑开，父容器横向滚动
    const cvs=document.getElementById('trendChart');
    cvs.style.width=(days.length*70)+'px';cvs.style.height='200px';
    cvs.parentElement.style.overflowX='auto';cvs.parentElement.style.overflowY='hidden';
    // 自动滚到最新（最右）
    setTimeout(()=>{cvs.parentElement.scrollLeft=cvs.parentElement.scrollWidth},100)
    trc=new Chart(ctx,{type:'line',data:{labels:days.map(d=>d.slice(5)),datasets:[{label:'支出',data:exp,borderColor:'#7C3AED',backgroundColor:'rgba(124,58,237,0.08)',fill:true,tension:0.4,pointBackgroundColor:'#7C3AED',pointRadius:3,borderWidth:2}]},options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{display:false}},scales:{x:{grid:{display:false},ticks:{color:'#A99EC4',font:{size:10}}},y:{grid:{color:'rgba(169,158,196,0.1)'},ticks:{color:'#A99EC4',font:{size:10},callback:v=>'¥'+v},beginAtZero:true}}}})
}

function switchView(name){
    ['flows','stats','members'].forEach(v=>{document.getElementById(v+'View').style.display=v===name?'block':'none'});
    document.querySelectorAll('.nav-item').forEach((n,i)=>{n.classList.toggle('active',(i===0&&name==='flows')||(i===1&&name==='stats')||(i===3&&name==='members'))});
    if(name==='stats')setTimeout(()=>loadMonth(),50);
    if(name==='members')renderMembers()
}

// ===== 成员管理 =====
function renderMembers(){
    const isOwner=members.some(m=>m.user_id===cu.id&&m.role==='owner');
    document.getElementById('memberList').innerHTML=members.length
        ? '<div style="padding:4px 0;">'+members.map(m=>'<div class="tx-item"><div class="tx-icon cat-other"><i class="ph ph-user"></i></div><div class="tx-info"><div class="tx-title">'+(m.name||'用户')+'</div><div class="tx-meta">'+(m.role==='owner'?'所有者':'成员')+'</div></div>'+(isOwner&&m.role!=='owner'?'<button class="member-remove-btn" onclick="removeMember('+m.user_id+')">移除</button>':'')+'</div>').join('')+'</div>'
        : '<div style="text-align:center;padding:30px;color:var(--text-muted);">暂无成员</div>'
}
async function removeMember(uid){
    if(!confirm('确定移除该成员？'))return;
    const r=await api('/api/entry/book/member-remove',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({book_id:bookId,target_user_id:uid})});
    if(r.code===200){toast('已移除','success');const mr=await api('/api/entry/book/members',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({book_id:bookId})});if(mr.code===200){members=mr.data||[];renderMembers()}}
    else toast(r.message||'移除失败','error')
}

// ===== 记账弹窗 =====
function openSheet(){
    mt='expense';
    document.getElementById('recordModal').classList.add('active');
    document.getElementById('modalDate').value=new Date().toISOString().split('T')[0];
    document.querySelectorAll('.type-option').forEach(e=>e.classList.remove('active'));
    document.querySelector('.type-option').classList.add('active');renderCatGrid();
    setTimeout(()=>document.getElementById('modalMoney').focus(),300)
}
function closeModal(e){if(!e||e.target.id==='recordModal')document.getElementById('recordModal').classList.remove('active')}
function setType(type,el){mt=type;document.querySelectorAll('.type-option').forEach(e=>e.classList.remove('active'));el.classList.add('active');renderCatGrid()}
async function saveFlow(){
    const money=parseFloat(document.getElementById('modalMoney').value.replace('¥',''))||0;
    if(!money){toast('请输入金额','error');return}
    const category=window._selectedCat||'其他';
    const day=document.getElementById('modalDate').value;
    if(!day){toast('请选择日期','error');return}
    const name=document.getElementById('modalName').value||category;
    const r=await api('/api/entry/flow/add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({book_id:bookId,day,flow_type:mt==='income'?'收入':'支出',industry_type:category,money,name,attribution:cu.name||cu.username})});
    if(r.code===200){
        const btn=document.querySelector('.nav-item.add-btn');
        if(btn){const r=btn.getBoundingClientRect();explodeConfetti(r.left+r.width/2,r.top+r.height/2)}
        toast('保存成功 ✨','success');closeModal();document.getElementById('modalMoney').value='¥0.00';document.getElementById('modalName').value='';window._selectedCat=null;
        await loadMonth()  // 刷新数据而非整页reload
    }
    else toast(r.message||'保存失败','error')
}
async function deleteFlow(fid){
    if(!confirm('确定删除这条流水？'))return;
    const r=await api('/api/entry/flow/'+fid,{method:'DELETE'});
    if(r.code===200){toast('已删除','success');await loadMonth()}else toast('删除失败','error')
}

// ===== 数字键盘 + 分类网格 =====
function inputNum(n){
    const inp=document.getElementById('modalMoney');
    let v=inp.value.replace('¥','');
    // 去掉小数部分，避免 3.00+5=3.005 的问题
    v=v.replace(/\.\d+$/,'');
    if(v==='0')v='';
    if(n==='.'&&v.includes('.'))return;
    v=v+n;
    inp.value='¥'+(parseFloat(v)||0).toFixed(2)
}
function inputBack(){
    const inp=document.getElementById('modalMoney');
    let v=inp.value.replace('¥','').replace(/\.\d+$/,'');
    v=v.slice(0,-1);
    inp.value=v?('¥'+(parseFloat(v)||0).toFixed(2)):'¥0.00'
}
function renderCatGrid(){
    const cats=mt==='income'
        ?[{n:'工资',i:'wallet',c:'#10B981'},{n:'奖金',i:'gift',c:'#F59E0B'},{n:'投资',i:'trend-up',c:'#3B82F6'},{n:'其他',i:'plus-circle',c:'#64748B'}]
        :[{n:'餐饮',i:'fork-knife',c:'#9333EA'},{n:'交通',i:'bus',c:'#4F46E5'},{n:'购物',i:'shopping-bag',c:'#DB2777'},{n:'娱乐',i:'film-strip',c:'#059669'},{n:'居住',i:'house',c:'#D97706'},{n:'医疗',i:'heartbeat',c:'#DC2626'},{n:'教育',i:'graduation-cap',c:'#0891B2'},{n:'其他',i:'dots-three',c:'#6B7280'}];
    document.getElementById('modalCatGrid').innerHTML=cats.map((c,i)=>'<div class="cat-option'+(i===0?' selected':'')+'" onclick="selectCat(this,\''+c.n+'\')"><i class="ph ph-'+c.i+'" style="color:'+c.c+';"></i><span>'+c.n+'</span></div>').join('')
}
function selectCat(el,name){
    document.querySelectorAll('#modalCatGrid .cat-option').forEach(e=>e.classList.remove('selected'));
    el.classList.add('selected');
    window._selectedCat=name
}

// ===== 分享 =====
let sk='';
async function showShareSheet(){
    document.getElementById('shareModal').classList.add('active');
    const r=await api('/api/entry/book/all');
    if(r.code===200){const b=r.data.find(x=>x.book_id===bookId);if(b){const sr=await api('/api/entry/book/share',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:b.id})});if(sr.code===200){sk=sr.data.share_key;document.getElementById('shareKeyDisplay').textContent=sk}}}
}
function closeShareModal(e){if(!e||e.target.id==='shareModal')document.getElementById('shareModal').classList.remove('active')}
function copyShareKey(){const el=document.getElementById('shareKeyDisplay');navigator.clipboard?.writeText(el.textContent).catch(()=>{});toast('已复制到剪贴板','success')}

init();
