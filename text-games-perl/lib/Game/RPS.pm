package Volity::Game::RPS;

use warnings;
use strict;

use base qw(Volity::Game);

our $player_class = "Volity::Player::RPS";

__PACKAGE__->max_allowed_players(2);
__PACKAGE__->min_allowed_players(2);
__PACKAGE__->uri("http://volity.org/games/rps");

sub player_class {
  return $player_class;
}

sub handle_normal_message {
  my $self = shift;
  my ($message) = @_;
#  print "Whoop, got a normal message.";
  return $self->handle_chat_message(@_);
}

sub handle_groupchat_message {
  my $self = shift;
  my ($message) = @_;
#  print "Whoop, got a groupchat message";
}

sub handle_chat_message {
  my $self = shift;
  my ($message)  = @_;
  my $body = $$message{body};
#  print "I got a message!! $body";
  unless (ref($self)) {
    # Eh, just a class method.
    warn "Eh, just a class method.\n";
    return $self->SUPER::handle_chat_message(@_);
  }
  my $player = $self->get_player_with_jid($$message{from});
  unless ($player) {
      # XXX Put nice error message here...
  }
  if (substr("rock", 0, length($body)) eq lc($body)) {
    $self->referee->send_message({
				 to=>$player->jid,
				 body=>"Good old rock! Nothing beats rock.",
			       });
    $player->hand_type('rock');
    
  } elsif (substr("paper", 0, length($body)) eq lc($body)) {
    $self->referee->send_message({
				 to=>$player->jid,
				 body=>"Paper it is.",
			       });
    $player->hand_type('paper');
  } elsif (substr("scissors", 0, length($body)) eq lc($body)) {
    $self->referee->send_message({
				 to=>$player->jid,
				 body=>"You chose scissors.",
			       });
    $player->hand_type('scissors');
  } else {
    $self->referee->send_message({
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
    $self->referee->groupchat($victory_message);
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

package Volity::Player::RPS;

use base qw(Volity::Player);
use fields qw(hand_type);

1;
