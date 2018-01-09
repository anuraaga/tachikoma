package com.sourceforgery.tachikoma.maildelivery.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mustachejava.DefaultMustacheFactory
import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import com.sourceforgery.tachikoma.common.toInstant
import com.sourceforgery.tachikoma.database.dao.EmailDAO
import com.sourceforgery.tachikoma.database.objects.EmailDBO
import com.sourceforgery.tachikoma.database.objects.EmailSendTransactionDBO
import com.sourceforgery.tachikoma.database.objects.id
import com.sourceforgery.tachikoma.database.server.DBObjectMapper
import com.sourceforgery.tachikoma.grpc.frontend.emptyToNull
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.EmailQueueStatus
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.EmailRecipient
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.MailDeliveryServiceGrpc
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.OutgoingEmail
import com.sourceforgery.tachikoma.grpc.frontend.maildelivery.Queued
import com.sourceforgery.tachikoma.grpc.frontend.toGrpcInternal
import com.sourceforgery.tachikoma.grpc.frontend.toNamedEmail
import com.sourceforgery.tachikoma.grpc.frontend.tracking.UrlTrackingData
import com.sourceforgery.tachikoma.identifiers.EmailId
import com.sourceforgery.tachikoma.mq.JobMessageFactory
import com.sourceforgery.tachikoma.mq.MQSender
import com.sourceforgery.tachikoma.tracking.TrackingDecoderImpl
import io.ebean.EbeanServer
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.lang.RuntimeException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Properties
import javax.inject.Inject
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

internal class MailDeliveryService
@Inject
private constructor(
        private val dbObjectMapper: DBObjectMapper,
        private val emailDAO: EmailDAO,
        private val mqSender: MQSender,
        private val jobMessageFactory: JobMessageFactory,
        private val ebeanServer: EbeanServer,
        private val trackingDecoderImpl: TrackingDecoderImpl
) : MailDeliveryServiceGrpc.MailDeliveryServiceImplBase() {

    override fun sendEmail(request: OutgoingEmail, responseObserver: StreamObserver<EmailQueueStatus>) {

        val transaction = EmailSendTransactionDBO(
                jsonRequest = getRequestData(request),
                fromEmail = request.from.toNamedEmail().address
        )
        val requestedSendTime =
                if (request.hasSendAt()) {
                    request.sendAt.toInstant()
                } else {
                    Instant.EPOCH
                }

        ebeanServer.createTransaction().use {
            for (recipient in request.recipientsList) {
                // TODO Check if recipient is blocked
                val emailDBO = EmailDBO(
                        recipient = recipient.toNamedEmail(),
                        transaction = transaction
                )
                emailDAO.save(emailDBO)

                emailDBO.body = when (request.bodyCase!!) {
                    OutgoingEmail.BodyCase.STATIC -> getStaticBody(request, emailDBO.id)
                    OutgoingEmail.BodyCase.TEMPLATE -> getTemplateBody(request, recipient, emailDBO.id)
                    else -> throw StatusRuntimeException(Status.INVALID_ARGUMENT)
                }
                emailDAO.save(emailDBO)

                mqSender.queueJob(jobMessageFactory.createSendEmailJob(
                        requestedSendTime = requestedSendTime,
                        emailId = emailDBO.id
                ))

                responseObserver.onNext(
                        EmailQueueStatus.newBuilder()
                                .setEmailId(emailDBO.id.toGrpcInternal())
                                .setQueued(Queued.getDefaultInstance())
                                .setTransactionId(transaction.id.toGrpcInternal())
                                .setRecipient(emailDBO.recipient.toGrpcInternal())
                                .build()
                )
            }
            throw RuntimeException("TEST")
//            responseObserver.onCompleted()
        }
    }

    private fun getTemplateBody(request: OutgoingEmail, recipient: EmailRecipient, emailId: EmailId): String {

        val template = request.template!!
        if (template.htmlTemplate == null && template.plaintextTemplate == null) {
            throw IllegalArgumentException("Needs at least one template (plaintext or html)")
        }

        val globalVarsStruct =
                if (template.hasGlobalVars()) {
                    template.globalVars
                } else {
                    Struct.getDefaultInstance()
                }
        val globalVars = unwrapStruct(globalVarsStruct)

        val recipientVars = unwrapStruct(recipient.templateVars)

        val htmlBody = mergeTemplate(template.htmlTemplate, globalVars, recipientVars)
        val plaintextBody = mergeTemplate(template.plaintextTemplate, globalVars, recipientVars)

        return wrapAndPackBody(
                request = request,
                htmlBody = htmlBody.emptyToNull(),
                plaintextBody = plaintextBody.emptyToNull(),
                subject = mergeTemplate(template.subject, globalVars, recipientVars),
                emailId = emailId
        )
    }

    private fun getStaticBody(request: OutgoingEmail, emailId: EmailId): String {
        val static = request.static!!
        val htmlBody = static.htmlBody.emptyToNull()
        val plaintextBody = static.plaintextBody.emptyToNull()

        return wrapAndPackBody(
                request = request,
                htmlBody = htmlBody,
                plaintextBody = plaintextBody,
                subject = static.subject,
                emailId = emailId
        )
    }

    private fun unwrapStruct(struct: Struct): HashMap<String, Any> {
        return dbObjectMapper.readValue<HashMap<String, Any>>(
                JsonFormat.printer().print(struct),
                object : TypeReference<HashMap<String, Any>>() {}
        )
    }

    // Store the request for later debugging
    private fun getRequestData(request: OutgoingEmail) =
            dbObjectMapper.readValue(PRINTER.print(request)!!, ObjectNode::class.java)!!

    private fun mergeTemplate(template: String?, vararg scopes: HashMap<String, Any>) =
            StringWriter().use {
                DefaultMustacheFactory()
                        .compile(StringReader(template), "html")
                        .execute(it, scopes)
                it.toString()
            }

    private fun wrapAndPackBody(request: OutgoingEmail, htmlBody: String?, plaintextBody: String?, subject: String, emailId: EmailId): String {
        if (htmlBody == null && plaintextBody == null) {
            throw IllegalArgumentException("Needs at least one of plaintext or html")
        }

        // TODO add headers here
        val session = Session.getDefaultInstance(Properties())
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(request.from.email, request.from.name))
        message.subject = subject

        for ((key, value) in request.headersMap) {
            message.addHeader(key, value)
        }

        val multipart = MimeMultipart("alternative")

        val htmlDoc = Jsoup.parse(htmlBody ?: "<html><body>$plaintextBody</body></html>")

        injectTrackingPixel(htmlDoc, emailId)

        val htmlPart = MimeBodyPart()
        htmlPart.setContent(htmlDoc.outerHtml(), "text/html")
        multipart.addBodyPart(htmlPart)

        val plaintextPart = MimeBodyPart()
        plaintextPart.setContent(plaintextBody ?: htmlDoc.text(), "text/plain")
        multipart.addBodyPart(plaintextPart)

        message.setContent(multipart)

        val result = ByteArrayOutputStream()
        message.writeTo(result)
        return result.toString(StandardCharsets.UTF_8.name())
    }

    private fun injectTrackingPixel(doc: Document, emailId: EmailId) {
        val trackingData = trackingDecoderImpl.createUrl(UrlTrackingData.newBuilder().setEmailId(emailId.toGrpcInternal()).build())

        // TODO Fix URI, add config param i.e. AND URIBuilder
        val trackingUri = URI.create("http://127.0.0.1:8070/t/$trackingData")

        val trackingPixel = Element("img")
        trackingPixel.attr("src", trackingUri.toString())
        trackingPixel.attr("height", "1")
        trackingPixel.attr("width", "1")
        doc.body().appendChild(trackingPixel)
    }

    companion object {
        val PRINTER = JsonFormat.printer()!!
    }
}
