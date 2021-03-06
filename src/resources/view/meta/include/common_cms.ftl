<#macro includePage path abbr=0>
<#local pageManager=beans['pageManager']>
<#if (Parameters.preview!)=='true' && (getSetting("cms.preview.open","false")=='true' || hasRole("ROLE_ADMINISTRATOR"))>
<#local page=pageManager.getDraftByPath(path)!>
<#if !page.content?has_content>
<#local page=pageManager.getByPath(path)!>
</#if>
<#else>
<#local page=pageManager.getByPath(path)!>
</#if>
<#if (page.content)??>
<#if abbr gt 0>
<#local _content=statics['org.ironrhino.core.util.HtmlUtils'].abbr(page.content, abbr)>
<#else>
<#local _content=page.content>
</#if>
<#local designMode=(Parameters.designMode!)=='true'&&abbr==0&&hasRole("ROLE_ADMINISTRATOR")>
<#if designMode>
<div class="editme" data-url="<@url value="/common/page/editme?id=${page.id}"/>" name="page.content">
</#if>
<@_content?interpret/>
<#if designMode>
</div>
</#if>
</#if>
</#macro>