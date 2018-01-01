extern crate grpc;
extern crate protobuf;
extern crate tls_api;
extern crate tls_api_rustls;
extern crate unix_socket;
extern crate url;
extern crate lettre;

mod generated_grpc;

use generated_grpc::empty::Empty;
use generated_grpc::message_queue::EmailMessage;
use generated_grpc::message_queue::IncomingEmailMessage;
use generated_grpc::message_queue::MTAQueuedNotification;
use generated_grpc::message_queue_grpc::MTAEmailQueueClient;
use generated_grpc::message_queue_grpc::MTAEmailQueue;

use grpc::Client;
use grpc::StreamingRequest;
use lettre::EmailAddress;
use lettre::EmailTransport;
use lettre::SimpleSendableEmail;
use lettre::smtp::ConnectionReuseParameters;
use lettre::smtp::ClientSecurity;
use lettre::smtp::SmtpTransportBuilder;
use std::env;
use std::io::BufReader;
use std::ops::Deref;
use std::sync::Arc;
use std::thread;
use std::vec::Vec;
use tls_api_rustls::TlsConnector;
use unix_socket::UnixListener;
use unix_socket::UnixStream;
use url::Url;

const LMTP_SOCKET_PATH: &'static str = "/var/spool/postfix/private/incoming_tachikoma";
const SMTP_PORT: u16 = 465;


fn handle_client(stream: UnixStream, mta_queue_client: &MTAEmailQueueClient) {
    let _buf_reader = BufReader::new(stream);
    // Become LMTP Server to read email.

    let mut incoming_email_message = IncomingEmailMessage::new();
    incoming_email_message.set_emailAddress(String::from("foobar@example.com"));
    incoming_email_message.set_body(b"Long freaking body".to_vec());
    mta_queue_client.incoming_email(grpc::RequestOptions::new(), incoming_email_message);
}

fn setup_grpc() -> MTAEmailQueueClient {
    let args: Vec<String> = env::args().collect();

    let url = Url::parse(&args[0]).expect("First argument must be the url of the server");

    let host = url.host_str().expect("URL needs to have a hostname");
    let port = url.port();
    let conf = grpc::ClientConf::new();

    let client = match url.scheme() {
        "http" => Client::new_plain(host, port.unwrap_or(80), conf),
        "https" => Client::new_tls::<TlsConnector>(host, port.unwrap_or(443), conf),
        _ => panic!("Neither http nor https!")
    }.expect(format!("Could not connect to {}", url).as_ref());
    return MTAEmailQueueClient::with_client(client);
}

fn send_email(mut email_message: EmailMessage) -> Result<Vec<MTAQueuedNotification>, lettre::smtp::error::Error> {
    let from = EmailAddress::new(email_message.take_from());
    let body = email_message.take_body();
    let sender_domain = email_message.take_senderDomain();

    let result = Vec::new();

    // Open a local connection on port 25
    let mut mailer = SmtpTransportBuilder::new(("localhost", SMTP_PORT), ClientSecurity::None)?
        .connection_reuse(ConnectionReuseParameters::NoReuse)
        .build();


    // Send the emails
    for receiver in email_message.get_emailAddresses() {
        let email = SimpleSendableEmail::new(
            from.clone(),
            vec![EmailAddress::new(receiver.clone())],
            "".to_string(),
            "Hello ß☺ example".to_string(),
        );

        let mailer_result = mailer.send(&email);

        if let Ok(mailer_response) = mailer_result {
            let result_lines_with_message_id = mailer_response.message;
            println!("Email sent");
        } else {
            println!("Could not send email: {:?}", mailer_result);
        }
    }

    println!("Should've sent message {:?}", email_message);

    return Ok(result);
}

fn listen_for_emails(mta_queue_client: &MTAEmailQueueClient) {
    // TODO Doesn't work at all since I can't set up a StreamingRequest
//    let stream = grpc::GrpcStream::new();
//    let mta_delivery_notifications_stream = StreamingRequest::new();
//    let email_stream = mta_queue_client.get_emails(grpc::RequestOptions::new(), Empty::new());
//    email_stream.map_items(move |email_message| send_email(email_message, mta_delivery_notifications_stream));
}

fn main() {
    let mta_queue_client = Arc::new(setup_grpc());

    let reference_counted = Arc::clone(&mta_queue_client);
    thread::spawn(move || listen_for_emails(reference_counted.deref()));

    let listener = UnixListener::bind(LMTP_SOCKET_PATH)
        .expect(&format!("Couldn't open socket {}", LMTP_SOCKET_PATH));

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                /* connection succeeded */
                let reference_counted = Arc::clone(&mta_queue_client);
                thread::spawn(move || handle_client(stream, reference_counted.deref()));
            }
            Err(_err) => {
                /* connection failed */
                println!("Failed connection {}", _err.to_string());
                break;
            }
        }
    }
}
