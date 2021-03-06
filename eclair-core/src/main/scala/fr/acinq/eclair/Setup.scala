package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props, SupervisorStrategy}
import akka.http.scaladsl.Http
import akka.pattern.after
import akka.stream.{ActorMaterializer, BindFailedException}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.eclair.NodeParams.{BITCOIND, BITCOINJ, ELECTRUM}
import fr.acinq.eclair.api.{GetInfoResponse, Service}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.bitcoind.{BitcoinCoreWallet, ZmqWatcher}
import fr.acinq.eclair.blockchain.bitcoinj.{BitcoinjKit, BitcoinjWallet, BitcoinjWatcher}
import fr.acinq.eclair.blockchain.electrum.{ElectrumClient, ElectrumEclairWallet, ElectrumWallet, ElectrumWatcher}
import fr.acinq.eclair.blockchain.fee.{ConstantFeeProvider, _}
import fr.acinq.eclair.blockchain.{EclairWallet, _}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.{Server, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

/**
  * Created by PM on 25/01/2016.
  */
class Setup(datadir: File, overrideDefaults: Config = ConfigFactory.empty(), actorSystem: ActorSystem = ActorSystem()) extends Logging {

  logger.info(s"hello!")
  logger.info(s"version=${getClass.getPackage.getImplementationVersion} commit=${getClass.getPackage.getSpecificationVersion}")

  val config = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val nodeParams = NodeParams.makeNodeParams(datadir, config)
  val chain = config.getString("chain")

  // early checks
  DBCompatChecker.checkDBCompatibility(nodeParams)
  PortChecker.checkAvailable(config.getString("server.binding-ip"), config.getInt("server.port"))

  logger.info(s"nodeid=${nodeParams.privateKey.publicKey.toBin} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  logger.info(s"initializing secure random generator")
  // this will force the secure random instance to initialize itself right now, making sure it doesn't hang later (see comment in package.scala)
  secureRandom.nextInt()

  implicit val system = actorSystem
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)
  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  val bitcoin = nodeParams.watcherType match {
    case BITCOIND =>
      val bitcoinClient = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
        user = config.getString("bitcoind.rpcuser"),
        password = config.getString("bitcoind.rpcpassword"),
        host = config.getString("bitcoind.host"),
        port = config.getInt("bitcoind.rpcport")))
      val future = for {
        json <- bitcoinClient.rpcClient.invoke("getblockchaininfo").recover { case _ => throw BitcoinRPCConnectionException }
        progress = (json \ "verificationprogress").extract[Double]
        chainHash <- bitcoinClient.rpcClient.invoke("getblockhash", 0).map(_.extract[String]).map(BinaryData(_)).map(x => BinaryData(x.reverse))
        bitcoinVersion <- bitcoinClient.rpcClient.invoke("getnetworkinfo").map(json => (json \ "version")).map(_.extract[String])
        unspentAddresses <- bitcoinClient.listUnspentAddresses
      } yield (progress, chainHash, bitcoinVersion, unspentAddresses)
      // blocking sanity checks
      val (progress, chainHash, bitcoinVersion, unspentAddresses) = Await.result(future, 10 seconds)
      assert(chainHash == nodeParams.chainHash, s"chainHash mismatch (conf=${nodeParams.chainHash} != bitcoind=$chainHash)")
      if (chainHash == Block.TestnetGenesisBlock.hash) {
        assert(unspentAddresses.forall(isSegwitAddress), "In testnet mode, make sure that all your UTXOs are p2sh-of-p2wpkh (check out our README for more details)")
      }
      assert(progress > 0.99, "bitcoind should be synchronized")
      // TODO: add a check on bitcoin version?

      Bitcoind(bitcoinClient)
    case BITCOINJ =>
      logger.warn("EXPERIMENTAL BITCOINJ MODE ENABLED!!!")
      val staticPeers = config.getConfigList("bitcoinj.static-peers").map(c => new InetSocketAddress(c.getString("host"), c.getInt("port"))).toList
      logger.info(s"using staticPeers=$staticPeers")
      val bitcoinjKit = new BitcoinjKit(chain, datadir, staticPeers)
      bitcoinjKit.startAsync()
      Await.ready(bitcoinjKit.initialized, 10 seconds)
      Bitcoinj(bitcoinjKit)
    case ELECTRUM =>
      logger.warn("EXPERIMENTAL ELECTRUM MODE ENABLED!!!")
      val addressesFile = chain match {
        case "test" => "/electrum/servers_testnet.json"
        case "regtest" => "/electrum/servers_regtest.json"
      }
      val stream = classOf[Setup].getResourceAsStream(addressesFile)
      val addresses = ElectrumClient.readServerAddresses(stream)
      val electrumClient = system.actorOf(SimpleSupervisor.props(Props(new ElectrumClient(addresses)), "electrum-client", SupervisorStrategy.Resume))
      Electrum(electrumClient)
  }

  def bootstrap: Future[Kit] = {
    val zmqConnected = Promise[Boolean]()
    val tcpBound = Promise[Unit]()

    val defaultFeerates = FeeratesPerByte(block_1 = config.getLong("default-feerates.delay-blocks.1"), blocks_2 = config.getLong("default-feerates.delay-blocks.2"), blocks_6 = config.getLong("default-feerates.delay-blocks.6"), blocks_12 = config.getLong("default-feerates.delay-blocks.12"), blocks_36 = config.getLong("default-feerates.delay-blocks.36"), blocks_72 = config.getLong("default-feerates.delay-blocks.72"))
    Globals.feeratesPerByte.set(defaultFeerates)
    Globals.feeratesPerKw.set(FeeratesPerKw(defaultFeerates))
    logger.info(s"initial feeratesPerByte=${Globals.feeratesPerByte.get()}")
    val feeProvider = (chain, bitcoin) match {
      case ("regtest", _) => new ConstantFeeProvider(defaultFeerates)
      case (_, Bitcoind(client)) => new FallbackFeeProvider(new BitgoFeeProvider() :: new EarnDotComFeeProvider() :: new BitcoinCoreFeeProvider(client.rpcClient, defaultFeerates) :: new ConstantFeeProvider(defaultFeerates) :: Nil) // order matters!
      case _ => new FallbackFeeProvider(new BitgoFeeProvider() :: new EarnDotComFeeProvider() :: new ConstantFeeProvider(defaultFeerates) :: Nil) // order matters!
    }
    system.scheduler.schedule(0 seconds, 10 minutes)(feeProvider.getFeerates.map {
      case feerates: FeeratesPerByte =>
        Globals.feeratesPerByte.set(feerates)
        Globals.feeratesPerKw.set(FeeratesPerKw(defaultFeerates))
        system.eventStream.publish(CurrentFeerates(Globals.feeratesPerKw.get))
        logger.info(s"current feeratesPerByte=${Globals.feeratesPerByte.get()}")
    })

    val watcher = bitcoin match {
      case Bitcoind(bitcoinClient) =>
        system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmq"), Some(zmqConnected))), "zmq", SupervisorStrategy.Restart))
        system.actorOf(SimpleSupervisor.props(ZmqWatcher.props(bitcoinClient), "watcher", SupervisorStrategy.Resume))
      case Bitcoinj(bitcoinj) =>
        zmqConnected.success(true)
        system.actorOf(SimpleSupervisor.props(BitcoinjWatcher.props(bitcoinj), "watcher", SupervisorStrategy.Resume))
      case Electrum(electrumClient) =>
        zmqConnected.success(true)
        system.actorOf(SimpleSupervisor.props(Props(new ElectrumWatcher(electrumClient)), "watcher", SupervisorStrategy.Resume))
    }

    val wallet = bitcoin match {
      case Bitcoind(bitcoinClient) => new BitcoinCoreWallet(bitcoinClient.rpcClient)
      case Bitcoinj(bitcoinj) => new BitcoinjWallet(bitcoinj.initialized.map(_ => bitcoinj.wallet()))
      case Electrum(electrumClient) =>
        val electrumSeedPath = new File(datadir, "electrum_seed.dat")
        val electrumWallet = system.actorOf(ElectrumWallet.props(electrumSeedPath, electrumClient, ElectrumWallet.WalletParameters(Block.RegtestGenesisBlock.hash, allowSpendUnconfirmed = true)), "electrum-wallet")
        new ElectrumEclairWallet(electrumWallet)
    }
    wallet.getFinalAddress.map {
      case address => logger.info(s"initial wallet address=$address")
    }

    val paymentHandler = system.actorOf(SimpleSupervisor.props(config.getString("payment-handler") match {
      case "local" => LocalPaymentHandler.props(nodeParams)
      case "noop" => Props[NoopPaymentHandler]
    }, "payment-handler", SupervisorStrategy.Resume))
    val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
    val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, register, paymentHandler), "relayer", SupervisorStrategy.Resume))
    val router = system.actorOf(SimpleSupervisor.props(Router.props(nodeParams, watcher), "router", SupervisorStrategy.Resume))
    val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, watcher, router, relayer, wallet), "switchboard", SupervisorStrategy.Resume))
    val paymentInitiator = system.actorOf(SimpleSupervisor.props(PaymentInitiator.props(nodeParams.privateKey.publicKey, router, register), "payment-initiator", SupervisorStrategy.Restart))
    val server = system.actorOf(SimpleSupervisor.props(Server.props(nodeParams, switchboard, new InetSocketAddress(config.getString("server.binding-ip"), config.getInt("server.port")), Some(tcpBound)), "server", SupervisorStrategy.Restart))

    val kit = Kit(
      nodeParams = nodeParams,
      system = system,
      watcher = watcher,
      paymentHandler = paymentHandler,
      register = register,
      relayer = relayer,
      router = router,
      switchboard = switchboard,
      paymentInitiator = paymentInitiator,
      server = server,
      wallet = wallet)

    val api = new Service {

      override def getInfoResponse: Future[GetInfoResponse] = Future.successful(GetInfoResponse(nodeId = nodeParams.privateKey.publicKey, alias = nodeParams.alias, port = config.getInt("server.port"), chainHash = nodeParams.chainHash, blockHeight = Globals.blockCount.intValue()))

      override def appKit = kit
    }
    val httpBound = Http().bindAndHandle(api.route, config.getString("api.binding-ip"), config.getInt("api.port")).recover {
      case _: BindFailedException => throw TCPBindException(config.getInt("api.port"))
    }

    val zmqTimeout = after(5 seconds, using = system.scheduler)(Future.failed(BitcoinZMQConnectionTimeoutException))
    val tcpTimeout = after(5 seconds, using = system.scheduler)(Future.failed(TCPBindException(config.getInt("server.port"))))
    val httpTimeout = after(5 seconds, using = system.scheduler)(Future.failed(TCPBindException(config.getInt("api.port"))))

    for {
      _ <- Future.firstCompletedOf(zmqConnected.future :: zmqTimeout :: Nil)
      _ <- Future.firstCompletedOf(tcpBound.future :: tcpTimeout :: Nil)
      _ <- Future.firstCompletedOf(httpBound :: httpTimeout :: Nil)
    } yield kit

  }

}


// @formatter:off
sealed trait Bitcoin
case class Bitcoind(extendedBitcoinClient: ExtendedBitcoinClient) extends Bitcoin
case class Bitcoinj(bitcoinjKit: BitcoinjKit) extends Bitcoin
case class Electrum(electrumClient: ActorRef) extends Bitcoin
// @formatter:on

case class Kit(nodeParams: NodeParams,
               system: ActorSystem,
               watcher: ActorRef,
               paymentHandler: ActorRef,
               register: ActorRef,
               relayer: ActorRef,
               router: ActorRef,
               switchboard: ActorRef,
               paymentInitiator: ActorRef,
               server: ActorRef,
               wallet: EclairWallet)

case object BitcoinZMQConnectionTimeoutException extends RuntimeException("could not connect to bitcoind using zeromq")

case object BitcoinRPCConnectionException extends RuntimeException("could not connect to bitcoind using json-rpc")
