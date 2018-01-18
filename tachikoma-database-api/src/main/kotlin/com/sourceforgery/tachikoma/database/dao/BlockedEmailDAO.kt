package com.sourceforgery.tachikoma.database.dao

import com.sourceforgery.tachikoma.common.BlockedReason
import com.sourceforgery.tachikoma.common.Email
import com.sourceforgery.tachikoma.database.objects.EmailStatusEventDBO

interface BlockedEmailDAO {
    fun getBlockedReason(from: Email, recipient: Email): BlockedReason?
    fun block(statusEvent: EmailStatusEventDBO)
    fun unblock(statusEventDBO: EmailStatusEventDBO)
}