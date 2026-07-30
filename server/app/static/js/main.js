/* 珍智账 SPA — 首页按月 · 统计日历 · 日期抽屉 */
let books=[],currentBookId='',flows=[],allFlows=[],recordType='expense',homeChart=null,pieChart=null;
let flowPage=0,flowPageSize=20,flowLoading=false;
let calYear=new Date().getFullYear(),calMonth=new Date().getMonth()+1;
let selectedCategory=null;
const user=JSON.parse(localStorage.getItem('user')||sessionStorage.getItem('user')||'{}');

// ===== 分类图标映射（全局，供分类明细展开复用）=====
function _catCls(c){return {餐饮:'cat-food',交通:'cat-transport',购物:'cat-shopping',居住:'cat-housing',通讯:'cat-communication',娱乐:'cat-entertainment',医疗:'cat-medical',教育:'cat-education',转账:'cat-transfer',理财:'cat-investment',工资:'cat-salary',还款:'cat-repayment'}[c]||'cat-other'}
function _catIcon(c){return {餐饮:'fork-knife',交通:'bus',购物:'shopping-bag',居住:'house',通讯:'phone',娱乐:'film-strip',医疗:'heartbeat',教育:'graduation-cap',转账:'arrows-left-right',理财:'trending-up',工资:'currency-cny',还款:'credit-card'}[c]||'dots-three'}

// ===== 页面切换 =====
function switchPage(name,btn){
    document.querySelectorAll('.page').forEach(p=>p.style.display='none');
    document.getElementById('page-'+name).style.display='block';
    document.querySelectorAll('.nav-item').forEach(n=>n.classList.remove('active'));
    if(btn)btn.classList.add('active');
    if(name==='home')loadHome();
    if(name==='stats')loadStatsPage();
    if(name==='categories')loadCategoryPage();
    if(name==='profile')loadProfile()
}

// ===== 首页 — 按月支出趋势 =====
async function loadHome(){
    try{
        const r=await api('/api/entry/book/all');
        flowPage=0;document.getElementById('homePeriod').textContent=new Date().toISOString().slice(0,7);
        if(r.code===200&&r.data.length){
            books=r.data;
            renderBookSelector();
            if(!currentBookId||!books.find(b=>b.book_id===currentBookId))currentBookId=books[0].book_id;
            const all=await api('/api/entry/flow/all?bookId='+currentBookId);
            if(all.code===200)allFlows=all.data||[];
            renderHome()
        }else{
            document.getElementById('homeBookSelector').innerHTML='';
            document.getElementById('balance').textContent='¥0.00';
            document.getElementById('incomeDisplay').textContent='¥0';
            document.getElementById('expenseDisplay').textContent='¥0';
            document.getElementById('recentList').innerHTML='<div style="text-align:center;padding:30px;color:var(--text-muted);font-size:0.85rem;">还没有账本</div>';
            if(homeChart)homeChart.destroy()
        }
    }catch(e){toast('加载失败:'+e.message,'error')}
}

function renderBookSelector(){
    const el=document.getElementById('homeBookSelector');
    el.innerHTML=books.map(b=>'<div class="book-pill'+(b.book_id===currentBookId?' active':'')+'" onclick="selectBook(\''+b.book_id+'\',this)">'+
        '<i class="ph ph-book" style="margin-right:6px;font-size:0.7rem;"></i>'+b.book_name+'</div>').join('')
}

async function selectBook(bid,el){flowPage=0;
    document.querySelectorAll('#homeBookSelector .book-pill').forEach(p=>p.classList.remove('active'));
    el.classList.add('active');
    currentBookId=bid;
    const all=await api('/api/entry/flow/all?bookId='+bid);
    if(all.code===200)allFlows=all.data||[];
    renderHome()
}

function renderHome(){
    // 本月统计
    const ms=new Date().toISOString().slice(0,7);
    const thisMonth=allFlows.filter(f=>(f.day||'').startsWith(ms));
    let inc=0,exp=0;
    thisMonth.forEach(f=>{const a=f.money||0;if(f.flow_type==='收入')inc+=a;else if(f.flow_type==='支出')exp+=a});
    animateNumber(document.getElementById('balance'), inc-exp);
    document.getElementById('incomeDisplay').textContent='¥'+inc.toFixed(0);
    document.getElementById('expenseDisplay').textContent='¥'+exp.toFixed(0);
    // 最近 — 分页渲染
    const recent=allFlows.sort((a,b)=>(b.day||'').localeCompare(a.day||''));
    const list=document.getElementById('recentList');
    if(!recent.length){list.innerHTML='<div style="text-align:center;padding:30px;color:var(--text-muted);font-size:0.85rem;">还没有记录</div>';return}
    flowPage=Math.min(flowPage, Math.ceil(recent.length/flowPageSize)-1);
    renderFlowPage();
    drawMonthlyChart();
    initFlowScroll()
}
function renderFlowPage(){
    const recent=allFlows.sort((a,b)=>(b.day||'').localeCompare(a.day||''));
    const list=document.getElementById('recentList');
    const end=(flowPage+1)*flowPageSize;
    const items=recent.slice(0,end);
    list.innerHTML=items.map((f,i)=>{
        const ic=f.flow_type==='收入';
        const cls=_catCls(f.industry_type);
        const icon=_catIcon(f.industry_type);
        return '<div class="tx-item stagger" style="animation-delay:'+((i%20)*0.05)+'s;"><div class="tx-icon '+cls+'"><i class="ph ph-'+icon+'"></i></div><div class="tx-info"><div class="tx-title">'+(f.name||f.industry_type||'未分类')+'</div><div class="tx-meta">'+f.day+'</div></div><div class="tx-amount '+(ic?'income':'expense')+'">'+(ic?'+':'-')+'¥'+(f.money||0).toFixed(2)+'</div></div>'
    }).join('');
    // 底部加载指示器
    if(end<recent.length)list.innerHTML+='<div id="flowLoader" style="text-align:center;padding:16px 0;"><div class="loader-dots"><span></span><span></span><span></span></div></div>';
}
function loadMoreFlows(){
    if(flowLoading)return;
    const recent=allFlows.sort((a,b)=>(b.day||'').localeCompare(a.day||''));
    const end=(flowPage+1)*flowPageSize;
    if(end>=recent.length)return;
    flowLoading=true;
    flowPage++;
    renderFlowPage();
    flowLoading=false
}
// 滚动加载
let flowScrollBound=false;
function initFlowScroll(){
    if(flowScrollBound)return;
    flowScrollBound=true;
    window.addEventListener('scroll',function(){
        if(flowLoading)return;
        const el=document.getElementById('flowLoader');
        if(!el)return;
        const rect=el.getBoundingClientRect();
        if(rect.top<window.innerHeight+100)loadMoreFlows()
    })
}

function drawMonthlyChart(){
    if(typeof Chart==='undefined')return;
    // 按月份聚合支出
    const monthly={};
    allFlows.forEach(f=>{
        if(f.flow_type==='支出'&&f.day){const m=f.day.slice(0,7);monthly[m]=(monthly[m]||0)+(f.money||0)}
    });
    const months=Object.keys(monthly).sort();
    const ctx=document.getElementById('homeChart')?.getContext('2d');
    if(!ctx)return;
    if(homeChart)homeChart.destroy();
    if(!months.length){homeChart=null;return}
    // 显示最近8个月
    const recent=months.slice(-8);
    homeChart=new Chart(ctx,{
        type:'bar',
        data:{labels:recent,datasets:[{
            label:'支出',data:recent.map(m=>monthly[m]),
            backgroundColor:recent.map((_,i)=>i===recent.length-1?'#7C3AED':'rgba(124,58,237,0.2)'),
            borderColor:recent.map((_,i)=>i===recent.length-1?'#7C3AED':'transparent'),
            borderWidth:1,borderRadius:6
        }]},
        options:{
            responsive:true,maintainAspectRatio:false,
            plugins:{legend:{display:false}},
            scales:{
                x:{grid:{display:false},ticks:{color:'#A99EC4',font:{size:10}}},
                y:{grid:{color:'rgba(169,158,196,0.1)'},ticks:{color:'#A99EC4',font:{size:10},callback:v=>'¥'+v}}
            }
        }
    })
}

// ===== 统计 — 饼图 + 日历 =====
async function loadStatsPage(){
    if(!books.length){const r=await api('/api/entry/book/all');if(r.code===200)books=r.data||[]}
    if(!books.length)return;
    if(!currentBookId||!books.find(b=>b.book_id===currentBookId))currentBookId=books[0].book_id;
    const r=await api('/api/entry/flow/all?bookId='+currentBookId);
    if(r.code===200){allFlows=r.data||[];flows=allFlows}
    drawPie();drawCal()
}

function drawPie(){
    if(typeof Chart==='undefined')return;
    const ed={};let total=0;
    // 按日历当前月份过滤
    const ms=calYear+'-'+String(calMonth).padStart(2,'0');
    allFlows.forEach(f=>{
        if(f.flow_type==='支出'&&(f.day||'').startsWith(ms)){
            const c=f.industry_type||'其他';ed[c]=(ed[c]||0)+(f.money||0);total+=f.money||0
        }
    });
    const cats=Object.keys(ed);
    const ctx=document.getElementById('statsPieChart')?.getContext('2d');
    if(!ctx)return;
    if(pieChart)pieChart.destroy();
    if(!cats.length){pieChart=null;return}
    pieChart=new Chart(ctx,{
        type:'doughnut',
        data:{labels:cats,datasets:[{data:cats.map(c=>ed[c]),backgroundColor:['#7C3AED','#A78BFA','#F472B6','#34D399','#F59E0B','#EF4444','#06B6D4','#8B5CF6'],borderWidth:0}]},
        options:{responsive:true,maintainAspectRatio:false,plugins:{legend:{position:'bottom',labels:{color:'#A99EC4',font:{size:11},padding:12,usePointStyle:true}}}}
    })
}

function drawCal(){
    const grid=document.getElementById('calGrid');
    const fd=new Date(calYear,calMonth-1,1),ld=new Date(calYear,calMonth,0);
    const dim=ld.getDate(),swd=fd.getDay();
    const wd=['日','一','二','三','四','五','六'];
    let html=wd.map(d=>'<div class="cal-day-header" style="font-size:10px;">'+d+'</div>').join('');
    for(let i=0;i<swd;i++)html+='<div class="cal-day empty"></div>';
    const today=new Date().toISOString().split('T')[0];
    for(let d=1;d<=dim;d++){
        const ds=calYear+'-'+String(calMonth).padStart(2,'0')+'-'+String(d).padStart(2,'0');
        const isToday=ds===today;
        let dayExp=0,dayInc=0;
        allFlows.forEach(f=>{if(f.day===ds){if(f.flow_type==='支出')dayExp+=f.money||0;else if(f.flow_type==='收入')dayInc+=f.money||0}});
        const hasData=dayExp>0||dayInc>0;
        html+='<div class="cal-day'+(isToday?' today':'')+(hasData?' has':'')+'" onclick="showDateDetail(\''+ds+'\',this)" style="'+(isToday?'border:1.5px solid var(--accent);':'')+'cursor:pointer;position:relative;">'+
            '<div class="d-num">'+d+'</div>'+
            (dayExp>0?'<div class="d-exp" style="font-size:7px;">-¥'+dayExp.toFixed(0)+'</div>':'')+
            (dayInc>0?'<div class="d-inc" style="font-size:7px;">+¥'+dayInc.toFixed(0)+'</div>':'')+
            '</div>'
    }
    grid.innerHTML=html;
    document.getElementById('calLabel').textContent=calYear+'年'+calMonth+'月'
}
function changeCalMonth(d){calMonth+=d;if(calMonth>12){calMonth=1;calYear++}if(calMonth<1){calMonth=12;calYear--}drawCal();drawPie()}

// ===== 日期详情浮卡（从方块展开）=====
function showDateDetail(ds,el){
    document.getElementById('popoverTitle').textContent=ds;
    const dayFlows=allFlows.filter(f=>f.day===ds).sort((a,b)=>b.id-a.id);
    const content=document.getElementById('popoverContent');
    if(!dayFlows.length){content.innerHTML='<div style="text-align:center;padding:20px;color:var(--text-muted);font-size:0.85rem;">该日无记录</div>'}
    else{
        content.innerHTML=dayFlows.map(f=>{
            const ic=f.flow_type==='收入';
            const cls=_catCls(f.industry_type);
            const icon=_catIcon(f.industry_type);
            return '<div class="tx-item" style="padding:8px 0;"><div class="tx-icon '+cls+'" style="width:36px;height:36px;font-size:0.8rem;"><i class="ph ph-'+icon+'"></i></div><div class="tx-info"><div class="tx-title" style="font-size:0.85rem;">'+(f.name||f.industry_type||'未分类')+'</div><div class="tx-meta">'+(f.attribution||'我')+'</div></div><div class="tx-amount '+(ic?'income':'expense')+'" style="font-size:0.9rem;">'+(ic?'+':'-')+'¥'+(f.money||0).toFixed(2)+'</div></div>'
        }).join('')
    }
    const pop=document.getElementById('datePopover');
    const bk=document.getElementById('popoverBackdrop');
    // 始终从方块向有空间的方向展开
    if(el){
        const r=el.getBoundingClientRect();
        const pw=320, ph=Math.min(220, window.innerHeight*0.45);  // 固定估算高度
        // 优先下方（避开饼图），下方不够再上方
        let top=r.bottom+6;
        if(top+ph>window.innerHeight-10) top=r.top-ph-6;
        // 水平居中
        let left=r.left+(r.width-pw)/2;
        if(left<10) left=10;
        if(left+pw>window.innerWidth-10) left=window.innerWidth-pw-10;
        pop.style.top=Math.max(10,top)+'px'; pop.style.left=left+'px'
    }else{
        pop.style.top='50%';pop.style.left='50%';pop.style.transform='translate(-50%,-50%)'
    }
    pop.style.display='block';bk.style.display='block'
}
function closeDatePopover(){
    document.getElementById('datePopover').style.display='none';
    document.getElementById('popoverBackdrop').style.display='none'
}

// ===== 账本列表 =====
async function loadBookList(){
    const r=await api('/api/entry/book/all');
    if(r.code===200){
        books=r.data||[];
        const list=document.getElementById('bookList');
        const mg=document.getElementById('bookManageBar').style.display!=='none';
        if(!books.length){list.innerHTML='<div class="transaction-list"><div style="text-align:center;padding:30px;color:var(--text-muted);">还没有账本</div></div>';return}
        list.innerHTML='<div class="transaction-list">'+books.map((b,i)=>
            '<div class="tx-item" data-bid="'+b.book_id+'">'+
            (mg?'<input type="checkbox" class="book-cb" value="'+b.book_id+'" style="width:20px;height:20px;accent-color:var(--accent);flex-shrink:0;">':'')+
            '<div class="tx-icon" style="background:var(--accent-bg);color:var(--accent);"><i class="ph ph-book"></i></div>'+
            '<div class="tx-info"><div class="tx-title">'+b.book_name+'</div><div class="tx-meta">'+(b.share_key?'共享':'个人')+'</div></div>'+
            (mg?'':'<i class="ph ph-caret-right" style="color:var(--text-muted);font-size:0.8rem;"></i>')+'</div>'+
            (i<books.length-1?'<div style="height:0.5px;background:var(--border);margin:0 16px;"></div>':'')
        ).join('')+'</div>';
        // 管理模式点击勾选，普通模式跳转
        if(mg)document.querySelectorAll('#bookList .tx-item').forEach(el=>el.onclick=function(){const c=this.querySelector('.book-cb');if(c){c.checked=!c.checked;updateBookCheckCount()}});
        else document.querySelectorAll('#bookList .tx-item').forEach(el=>el.onclick=function(){location.href='/book/'+this.dataset.bid})
    }
}
function toggleBookManage(){
    const bar=document.getElementById('bookManageBar');
    const btn=document.getElementById('bookManageBtn');
    if(bar.style.display!=='none'){
        bar.style.display='none';
        btn.innerHTML='<i class="ph ph-pencil"></i> 管理';
    }else{
        bar.style.display='block';
        btn.innerHTML='<i class="ph ph-x"></i> 取消';
    }
    loadBookList()
}
function updateBookCheckCount(){
    const checked=document.querySelectorAll('#bookList input[type=checkbox]:checked').length;
    document.getElementById('bookCheckedCount').textContent=checked?'('+checked+')':'0'
}
async function batchDeleteBooks(){
    const checked=[...document.querySelectorAll('#bookList input[type=checkbox]:checked')].map(c=>c.value);
    if(!checked.length){toast('请选择账本','error');return}
    if(!confirm('确定删除选中的 '+checked.length+' 个账本？所有流水数据将永久删除！'))return;
    const r=await api('/api/entry/book/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({book_ids:checked})});
    if(r.code===200){
        toast('已删除 '+r.data.deleted+' 个账本','success');
        toggleBookManage();
        loadBookList()
    }else toast(r.message||'删除失败','error')
}

// ===== 我的 =====
function loadProfile(){
    const u=user;
    document.getElementById('avatarDisplay').innerHTML='<i class="ph ph-user-circle"></i> '+(u.name||u.username||'').charAt(0);
    document.getElementById('userNameDisplay').textContent=u.name||u.username||'用户';
    document.getElementById('userBooksCount').textContent=books.length+' 个账本';
    // 渲染账本列表
    const el=document.getElementById('profileBookList');
    if(!books.length){el.innerHTML='';return}
    el.innerHTML=books.map(b=>'<div class="tx-item" style="padding:8px 0;cursor:pointer;" onclick="location.href=\'/book/'+b.book_id+'\'">'+
        '<div class="tx-icon" style="background:var(--accent-bg);color:var(--accent);"><i class="ph ph-book"></i></div>'+
        '<div class="tx-info"><div class="tx-title" style="font-size:0.85rem;">'+b.book_name+'</div></div>'+
        '<span style="font-size:0.72rem;color:var(--text-muted);">›</span></div>'
    ).join('')
}

// ===== 分类明细 =====
async function loadCategoryPage(){
    if(!books.length)return;
    if(!currentBookId||!books.find(b=>b.book_id===currentBookId))currentBookId=books[0].book_id;
    if(!allFlows.length){const r=await api("/api/entry/flow/all?bookId="+currentBookId);if(r.code===200)allFlows=r.data||[]}
    renderCatSummary()
}
function renderCatSummary(){
    const monthly={};
    allFlows.forEach(f=>{
        if(f.flow_type==='支出'&&f.day){
            const m=f.day.slice(0,7);
            if(!monthly[m])monthly[m]={};
            const c=f.industry_type||'其他';
            monthly[m][c]=(monthly[m][c]||0)+(f.money||0)
        }
    });
    const months=Object.keys(monthly).sort().reverse();
    const cols=['#7C3AED','#A78BFA','#F472B6','#34D399','#F59E0B','#EF4444','#06B6D4','#8B5CF6'];
    const el=document.getElementById('catList');
    if(!months.length){el.innerHTML='<div style="text-align:center;padding:30px;color:var(--text-muted);font-size:0.85rem;">暂无支出</div>';return}
    el.innerHTML=months.map(m=>{
        const ed=monthly[m],cats=Object.keys(ed).sort((a,b)=>ed[b]-ed[a]);
        const total=cats.reduce((s,c)=>s+ed[c],0);
        const ms=m.slice(0,4)+'年'+parseInt(m.slice(5))+'月';
        return '<div style="margin-bottom:24px;">'+
            '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;padding:0 4px;">'+
            '<span style="font-size:0.9rem;font-weight:600;color:var(--text-primary);">'+ms+'</span>'+
            '<span style="font-size:0.8rem;color:var(--text-muted);">¥'+total.toFixed(0)+'</span></div>'+
            cats.map((c,i)=>{
                const p=ed[c]/total*100;
                const cid=m+'-'+c.replace(/[^a-zA-Z0-9\u4e00-\u9fa5]/g,'');
                return '<div style="background:var(--bg-card);border:1px solid var(--border);border-radius:14px;margin-bottom:4px;overflow:hidden;">'+
                    '<div style="display:flex;align-items:center;gap:12px;padding:10px 12px;cursor:pointer;" onclick="toggleCatExpand(\''+cid+'\',\''+c+'\')">'+
                    '<div class="tx-icon '+_catCls(c)+'"><i class="ph ph-'+_catIcon(c)+'"></i></div>'+
                    '<div style="flex:1;min-width:0;">'+
                    '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px;">'+
                    '<span style="font-size:0.85rem;font-weight:500;color:var(--text-primary);">'+c+'</span>'+
                    '<span style="font-size:0.78rem;color:var(--text-muted);">-¥'+(ed[c]||0).toFixed(0)+' · '+p.toFixed(1)+'%</span></div>'+
                    '<div style="height:8px;background:var(--border);border-radius:4px;overflow:hidden;">'+
                    '<div style="height:100%;width:'+p+'%;border-radius:4px;background:'+cols[i%8]+';transition:width 0.6s ease;"></div></div></div>'+
                    '<i class="ph ph-caret-down" style="color:var(--text-muted);font-size:0.9rem;transition:transform 0.25s;" id="arrow-'+cid+'"></i></div>'+
                    '<div id="expand-'+cid+'" style="display:none;border-top:1px solid var(--border);padding:8px 12px 8px 68px;"></div></div>'
            }).join('')+'</div>'
    }).join('')
}
function toggleCatExpand(cid,cat){
    const expand=document.getElementById('expand-'+cid);
    const arrow=document.getElementById('arrow-'+cid);
    if(!expand)return;
    if(expand.style.display==='block'){
        expand.style.display='none';
        if(arrow)arrow.style.transform='rotate(0deg)';
        return
    }
    // close all other expanded sections
    document.querySelectorAll('[id^="expand-"]').forEach(el=>{el.style.display='none'});
    document.querySelectorAll('[id^="arrow-"]').forEach(el=>{el.style.transform='rotate(0deg)'});
    // render flows for this category + month
    const monthKey=cid.slice(0,7);
    const filtered=allFlows.filter(f=>f.flow_type==='支出'&&(f.industry_type||'其他')===cat&&(f.day||'').startsWith(monthKey))
        .sort((a,b)=>(b.day||'').localeCompare(a.day||''));
    expand.innerHTML=filtered.length
        ? filtered.map(f=>'<div class="tx-item" style="padding:6px 0;min-height:auto;"><div class="tx-icon '+_catCls(cat)+'"><i class="ph ph-'+_catIcon(cat)+'"></i></div><div class="tx-info"><div class="tx-title" style="font-size:0.82rem;">'+(f.name||f.industry_type||'未分类')+'</div><div class="tx-meta">'+f.day+'</div></div><div class="tx-amount expense" style="font-size:0.85rem;">-¥'+(f.money||0).toFixed(2)+'</div></div>').join('')
        : '<div style="text-align:center;padding:12px;color:var(--text-muted);font-size:0.78rem;">无记录</div>';
    expand.style.display='block';
    if(arrow)arrow.style.transform='rotate(180deg)'
}

// ===== 记账弹窗 =====
function openRecordSheet(){
    recordType='expense';
    document.getElementById('recordModal').classList.add('active');
    document.getElementById('recordDate').value=new Date().toISOString().split('T')[0];
    const bs=document.getElementById('recordBook');
    bs.innerHTML=books.map((b,i)=>'<option value="'+b.book_id+'"'+(i===0?' selected':'')+'>'+b.book_name+'</option>').join('');
    updCats();
    setTimeout(()=>document.getElementById('recordAmount').focus(),300)
}
function closeModal(e){if(!e||e.target.id==='recordModal')document.getElementById('recordModal').classList.remove('active')}
function setRecordType(type,el){recordType=type;document.querySelectorAll('.type-option').forEach(e=>e.classList.remove('active'));el.classList.add('active');updCats()}
function updCats(){
    const sel=document.getElementById('recordCategory');
    sel.innerHTML=recordType==='income'
        ?'<option value="工资">工资</option><option value="奖金">奖金</option><option value="投资">投资</option><option value="其他收入">其他</option>'
        :'<option value="餐饮">餐饮</option><option value="交通">交通</option><option value="购物">购物</option><option value="居住">居住</option><option value="娱乐">娱乐</option><option value="医疗">医疗</option><option value="教育">教育</option><option value="其他">其他</option>'
}
async function submitRecord() {
    const bid=document.getElementById('recordBook').value;
    const money=parseFloat(document.getElementById('recordAmount').value.replace('¥',''))||0;
    if(!money){toast('请输入金额','error');return}
    const r=await api('/api/entry/flow/add',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({book_id:bid,day:document.getElementById('recordDate').value,flow_type:recordType==='income'?'收入':'支出',industry_type:document.getElementById('recordCategory').value,money,name:document.getElementById('recordName').value||document.getElementById('recordCategory').value,attribution:user.name||user.username})
    });
    if(r.code===200){
        // 彩纸爆炸 — 从底部导航中心按钮位置爆出
        const btn=document.querySelector('.nav-item.add-btn');
        if(btn){const r=btn.getBoundingClientRect();explodeConfetti(r.left+r.width/2,r.top+r.height/2)}
        toast('保存成功 ✨','success');closeModal();document.getElementById('recordAmount').value='¥0.00';document.getElementById('recordName').value='';
        loadHome()  // 刷新数据而非整页reload
    }
    else toast(r.message||'保存失败','error')
}

// ===== 新建/加入账本 =====
function showCreateBookSheet(){document.getElementById('createBookModal').classList.add('active')}
function closeCreateBook(e){if(!e||e.target.id==='createBookModal')document.getElementById('createBookModal').classList.remove('active')}
async function createBook(){
    const n=document.getElementById('newBookName').value.trim();
    if(!n){toast('请输入名称','error');return}
    const r=await api('/api/entry/book/add',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({book_name:n,budget:parseFloat(document.getElementById('newBookBudget').value)||0})});
    if(r.code===200){toast('创建成功 ✨','success');closeCreateBook();document.getElementById('newBookName').value='';loadBookList();switchPage('books',document.querySelectorAll('.nav-item')[3])}
    else toast(r.message||'创建失败','error')
}
function showJoinBookSheet(){document.getElementById('joinBookModal').classList.add('active')}
function closeJoinBook(e){if(!e||e.target.id==='joinBookModal')document.getElementById('joinBookModal').classList.remove('active')}
async function joinBook(){
    const k=document.getElementById('joinKey').value.trim();
    if(!k){toast('请输入密钥','error');return}
    const r=await api('/api/entry/book/inshare',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({key:k})});
    if(r.code===200){toast('加入成功 ✨','success');closeJoinBook();loadBookList();switchPage('books',document.querySelectorAll('.nav-item')[3])}
    else toast(r.message||'加入失败','error')
}

// ===== 数字键盘 =====
function numpadInput(n){
    const inp=document.getElementById('recordAmount');
    let v=inp.value.replace('¥','');
    v=v.replace(/\.\d+$/,'');
    if(v==='0')v='';
    if(n==='.'&&v.includes('.'))return;
    v=v+n;
    inp.value='¥'+(parseFloat(v)||0).toFixed(2)
}
function numpadBack(){
    const inp=document.getElementById('recordAmount');
    let v=inp.value.replace('¥','').replace(/\.\d+$/,'');
    v=v.slice(0,-1);
    inp.value=v?('¥'+(parseFloat(v)||0).toFixed(2)):'¥0.00'
}

function logout(){['token','user','rememberMe'].forEach(k=>localStorage.removeItem(k));['token','user'].forEach(k=>sessionStorage.removeItem(k));window.location.href='/login'}

switchPage('home',document.querySelector('.nav-item'))
