package com.sourceforgery.tachikoma.maildelivery

import com.google.protobuf.ByteString
import com.sourceforgery.tachikoma.DAOHelper
import com.sourceforgery.tachikoma.DatabaseBinder
import com.sourceforgery.tachikoma.MinimalBinder
import com.sourceforgery.tachikoma.TestBinder
import com.sourceforgery.tachikoma.database.dao.EmailDAO
import com.sourceforgery.tachikoma.database.objects.id
import com.sourceforgery.tachikoma.grpc.QueueStreamObserver
import com.sourceforgery.tachikoma.grpc.frontend.Attachment
import com.sourceforgery.tachikoma.grpc.frontend.NamedEmailAddress
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.EmailQueueStatus
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.EmailRecipient
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.OutgoingEmail
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.StaticBody
import com.sourceforgery.tachikoma.grpc.frontend.toEmailId
import com.sourceforgery.tachikoma.grpc.frontend.toNamedEmail
import com.sourceforgery.tachikoma.hk2.get
import com.sourceforgery.tachikoma.hk2.located
import com.sourceforgery.tachikoma.maildelivery.impl.MailDeliveryService
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.glassfish.hk2.api.ServiceLocator
import org.glassfish.hk2.utilities.ServiceLocatorUtilities
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.junit.Assert.assertEquals
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith

@RunWith(JUnitPlatform::class)
class MailDeliveryServiceSpec : Spek({
    lateinit var serviceLocator: ServiceLocator
    val mailDeliveryService: () -> MailDeliveryService = located { serviceLocator }
    val daoHelper: () -> DAOHelper = located { serviceLocator }

    beforeEachTest {
        serviceLocator = ServiceLocatorUtilities.bind(TestBinder(), DatabaseBinder(), MinimalBinder(MailDeliveryService::class.java))!!
    }
    afterEachTest {
        serviceLocator.shutdown()
    }

    describe("Send emails") {
        it("with attachment") {
            val authentication = daoHelper().createAuthentication(fromEmail.toNamedEmail().address.domain)
            val email = OutgoingEmail.newBuilder()
                .addRecipients(EmailRecipient.newBuilder().setNamedEmail(validEmail))
                .addAttachments(Attachment.newBuilder()
                    .setContentType("application/pdf")
                    .setData(ByteString.copyFrom(data))
                    .setFileName("NotReally.pdf"))
                .setFrom(fromEmail)
                .setStatic(StaticBody.newBuilder().setPlaintextBody(
                    """This is a test
                            |                                      .
                            |.
                            |.                 ${""}
                            |"""
                        .trimMargin()
                ).setSubject("Test mail subject"))
                .build()
            val responseObserver = QueueStreamObserver<EmailQueueStatus>()
            mailDeliveryService().sendEmail(
                request = email,
                sender = authentication.account.id,
                responseObserver = responseObserver,
                authenticationId = authentication.id
            )
            val emailDAO: EmailDAO = serviceLocator.get()
            val queued = responseObserver.take(500)
            val byEmailId = emailDAO.getByEmailId(queued.emailId.toEmailId())!!
            val boundary = Regex("\tboundary=\"(.*?)\"").find(byEmailId.body!!)!!.groupValues[1]

            val modifiedBody = byEmailId.body!!.replace(boundary, "XXXXXX")
                .replace(Regex("Date: .*"), "Date: XXXXX")

            val expected = this.javaClass.getResourceAsStream("/attachment_email.txt").use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            }
            assertEquals(expected, modifiedBody)
        }
    }
}) {
    companion object {
        val validEmail = NamedEmailAddress.newBuilder().setEmail("foo@example.com").setName("Valid Email").build()
        val fromEmail = NamedEmailAddress.newBuilder().setEmail("from@example.com").setName("Valid From Email").build()

        val data = Base64.getDecoder().decode("dt6J5W7J+3hrduLSGtgij5IQrnc=")!!
    }
}
