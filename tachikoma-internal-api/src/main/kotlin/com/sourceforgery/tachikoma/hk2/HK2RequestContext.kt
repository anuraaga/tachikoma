package com.sourceforgery.tachikoma.hk2

import org.glassfish.hk2.api.ServiceLocator

interface HK2RequestContext {
    fun <T> runInScope(task: (ServiceLocator) -> T): T
    fun getContextInstance(): ReqCtxInstance
    fun <T> runInScope(ctx: ReqCtxInstance, task: (ServiceLocator) -> T): T
}

interface ReqCtxInstance