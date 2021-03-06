package com.sourceforgery.tachikoma.mq.jobs

import com.sourceforgery.tachikoma.identifiers.MailDomain
import com.sourceforgery.tachikoma.mq.JobMessage
import com.sourceforgery.tachikoma.mq.MQSender
import com.sourceforgery.tachikoma.mq.OutgoingEmailMessage
import javax.inject.Inject
import org.apache.logging.log4j.kotlin.logger

class SendEmailJob
@Inject
private constructor(
    private val mqSender: MQSender
) : Job {
    override fun execute(jobMessage: JobMessage) {
        val sendEmailJob = jobMessage.sendEmailJob
        val outgoingEmail = OutgoingEmailMessage.newBuilder()
            .setEmailId(sendEmailJob.emailId)
            .setCreationTimestamp(jobMessage.creationTimestamp)
            .build()
        mqSender.queueOutgoingEmail(MailDomain(sendEmailJob.mailDomain), outgoingEmail)
        LOGGER.info { "Email with id ${jobMessage.sendEmailJob.emailId} is about to be put into outgoing queue" }
    }

    companion object {
        private val LOGGER = logger()
    }
}
