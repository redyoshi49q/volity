# This file defines a bunch of packages used by the Frivolity test suite.

package TestRPS::Player;

use base qw(Volity::Player);
use fields qw(hand_type);

1;

package TestRPS::Server;

use warnings; use strict;

use lib qw(.);

use base qw(Volity::Server);
use fields qw(has_authed);

use POE qw(
	   Wheel::SocketFactory
	   Wheel::ReadWrite
	   Filter::Line
	   Driver::SysRW
	   Component::Jabber;
	  );
use Jabber::NS qw(:all);

# Hijack the jabber_auth to set a secret value.

use Test::More;

sub jabber_authed {
  my $self = $_[OBJECT];
  $self->has_authed(1);
  Volity::Server::jabber_authed(@_);
  main::has_authed;		# Call test.pl's auth sub.
  $main::timestamp = time;
}

# Hijack the init method to not bother doing a require. (I grumble a
# little at this.)
sub initialize {
  my $self = shift;
  if ($self->Volity::Jabber::initialize(@_)) {
    my $referee_class = $self->referee_class or die
      "No referee class specified at construction!";
  }
  return $self;
}

package TestRPS::Referee;

# Game referee for Rock Paper Scissors.

use warnings;
use strict;

use base qw(Volity::Referee);
use Volity::GameRecord;
use Carp qw(croak);

# Hijack the init method to not bother doing a require. (I grumble a
# little at this.)
sub initialize {
  my $self = shift;
  $self->max_allowed_players(2);
  $self->min_allowed_players(2);
  $self->uri('http://volity.org/games/rps');
  $self->game_class("TestRPS::Game");
  $self->muc_host('conference.localhost');

  $self->Volity::Jabber::initialize(@_);

  # Build the JID of our MUC.
  unless (defined($self->muc_host)) {
    croak ("You must define a muc_host on referee construction.");
  }
  $self->muc_jid($self->resource . '@' . $self->muc_host);

  return $self;

}  

# We hijack send_record_to_bookkeeper, cuz we don't really want to do that.
sub send_record_to_bookkeeper {
  my $self = shift;
  my ($record) = @_;
  main::examine_record($record);
}


package TestRPS::Game;

use warnings;
use strict;

use base qw(Volity::Game);

our $player_class = "TestRPS::Player";

sub player_class {
  return $player_class;
}

sub handle_normal_message {
  my $self = shift;
  my ($message) = @_;
  print "Whoop, got a normal message.";
}

sub handle_groupchat_message {
  my $self = shift;
  my ($message) = @_;
  print "Whoop, got a groupchat message";
}

sub handle_chat_message {
  my $self = shift;
  my ($message)  = @_;
  my $body = $$message{body};
  print "I got a message!! $body";
  unless (ref($self)) {
    # Eh, just a class method.
    warn "Eh, just a class method.\n";
    return $self->SUPER::handle_chat_message(@_);
  }
  my $player = $self->get_player_with_jid($$message{from});

  warn "Message is from player $player, aka " . $player->jid;
  unless ($player) {
    use Data::Dumper;
    die "I'm stupid. The message was from $$message{from}. Here is some junk:\n" . Dumper($self->{player_jids});
  }
  if (substr("rock", 0, length($body)) eq lc($body)) {
    warn "ROCK";
    $self->server->send_message({
				 to=>$player->jid,
				 body=>"Good old rock! Nothing beats rock.",
			       });
    $player->hand_type('rock');
    
  } elsif (substr("paper", 0, length($body)) eq lc($body)) {
    $self->server->send_message({
				 to=>$player->jid,
				 body=>"Paper it is.",
			       });
    $player->hand_type('paper');
  } elsif (substr("scissors", 0, length($body)) eq lc($body)) {
    $self->server->send_message({
				 to=>$player->jid,
				 body=>"You chose scissors.",
			       });
    $player->hand_type('scissors');
  } else {
    $self->server->send_message({
				 to=>$player->jid,
				 body=>"No idea what you just said. Please choose one of 'rock', 'paper' or 'scissors'.",
			       });
  }
  
  # Has everyone registered a hand?
  if (grep(defined($_), map($_->hand_type, $self->players)) == $self->players) {
  # Yes! Time for BATTLE!
    my @players = sort( {
			 my $handa = $a->hand_type; my $handb = $b->hand_type;
			 if ($handa eq $handb) {
			   return 0;
			 } elsif ($handa eq 'rock' and $handb eq 'scissors') {
			   return -1;
			 } elsif ($handa eq 'scissors' and $handb eq 'paper') {
			   return -1;
			 } elsif ($handa eq 'paper' and $handb eq 'rock') {
			   return -1;
			 } else {
			   return 1;
			 }
		       }
			$self->players);
    my $victory_message;
    if ($players[0]->hand_type eq $players[-1]->hand_type) {
      $victory_message = sprintf("A tie! Both players chose %s.", $players[0]->hand_type);
    } elsif ($players[0]->hand_type eq 'rock') {
      $victory_message = sprintf("%s(rock) crushes %s(scissors)!", $players[0]->nick, $players[1]->nick);
      $self->winners($players[0]);
    } elsif ($players[0]->hand_type eq 'scissors') {
      $victory_message = sprintf("%s(scissors) shreds %s(paper)!", $players[0]->nick, $players[1]->nick);
      $self->winners($players[0]);
    } else {
      $victory_message = sprintf("%s(paper) smothers %s(rock)!", $players[0]->nick, $players[1]->nick);
      $self->winners($players[0]);
    }
    $self->server->send_message({
				 to=>$self->muc_jid,
				 body=>$victory_message,
			       });
    $self->end_game;
  }
  
}

sub jid_name {
  return "rps";
}

sub end_game {
  my $self = shift;
  for my $player ($self->players) {
    $player->hand_type(undef);
  }
  return $self->SUPER::end_game(@_);
}

package TestRPS::Player;

use base qw(Volity::Player);
use fields qw(hand_type);
